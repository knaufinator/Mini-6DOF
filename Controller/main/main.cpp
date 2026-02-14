/*
 * Mini-6DOF Stewart Platform Controller - ESP-IDF Native Implementation
 *
 * PWM hobby-servo variant of the Stewart Platform controller.
 * Shares the same serial command API and protocol as the main controller
 * for full compatibility with the desktop HIL entity system.
 *
 * Key differences from the main (stepper) controller:
 *   - LEDC PWM output for hobby servos (50 Hz, 800-2200 µs)
 *   - No MCPWM, no step/dir GPIO, no planetary gearbox
 *   - Smaller platform geometry defaults
 *   - Servo angle → pulse width mapping instead of step counting
 */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>

// ESP-IDF headers
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_system.h"
#include "esp_mac.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "driver/ledc.h"
#include "driver/gpio.h"
#include <fcntl.h>
#include <unistd.h>
#include "nvs_flash.h"
#include "esp_task_wdt.h"

// Project headers
#include "helpers.h"
#include "debug_uart.h"
#include "InverseKinematics.h"
#include "AxisScaling.h"
#include "version.h"

static const char* TAG = "mini6dof";

// ── Debug Output ─────────────────────────────────────────────────────
#ifdef ENABLE_DEBUG_UART
bool debugEnabled = false;
#endif

// ── Thread-safe serial printf ────────────────────────────────────────
static SemaphoreHandle_t xPrintMutex = NULL;

#define serial_printf(fmt, ...) do { \
    if (xPrintMutex) xSemaphoreTake(xPrintMutex, portMAX_DELAY); \
    printf(fmt, ##__VA_ARGS__); \
    fflush(stdout); \
    if (xPrintMutex) xSemaphoreGive(xPrintMutex); \
} while(0)

// ── Platform Configuration ───────────────────────────────────────────
static StewartConfig stewartConfig;
static AxisScaleConfig axisScales;

// Mini-6DOF specific defaults (geometry in mm, converted from inches)
static void initMiniDefaults(StewartConfig* cfg) {
    cfg->theta_r = 10.0f;
    cfg->theta_s[0] = 150.0f; cfg->theta_s[1] = -90.0f; cfg->theta_s[2] = 30.0f;
    cfg->theta_s[3] = 150.0f; cfg->theta_s[4] = -90.0f; cfg->theta_s[5] = 30.0f;
    cfg->theta_p = 30.0f;
    cfg->RD = 15.75f;               // base radius (mm — original Mini uses mm directly)
    cfg->PD = 16.0f;                // platform radius (mm)
    cfg->ServoArmLengthL1 = 7.25f;  // servo horn length (mm)
    cfg->ConnectingArmLengthL2 = 28.5f; // connecting rod length (mm)
    cfg->platformHeight = 25.517f;   // neutral height (mm)

    // Drive train not used for PWM servos — kept for API compat
    cfg->virtual_gear = 1.0f;
    cfg->planetary_ratio = 1.0f;
    cfg->encoder_ppr = 1;
    cfg->steps_per_degree = 1.0f;
}

// ── Input Scaling ────────────────────────────────────────────────────
static uint8_t inputBitRange = 12;
static float maxRawInput = 4095.0f;

// ── Motion State ─────────────────────────────────────────────────────
static volatile float arr[6] = {0, 0, 0, 0, 0, 0};  // current IK position (physical units)
static SemaphoreHandle_t xMutex = NULL;

// ── Servo PWM ────────────────────────────────────────────────────────
static const int servoPins[6] = {
    SERVO_PIN_0, SERVO_PIN_1, SERVO_PIN_2,
    SERVO_PIN_3, SERVO_PIN_4, SERVO_PIN_5
};

// Servo center calibration (µs) — can be adjusted per-servo
static int servoCenter[6] = {1500, 1500, 1500, 1500, 1500, 1500};

// Pulse width multiplier: µs per radian of servo arm rotation
static float servoPulsePerRad = 800.0f / (float)(IK_PI / 4.0);

// Inverted servos (mounted mirrored)
static const bool servoInverted[6] = {true, false, true, false, true, false};

// ── LEDC PWM Setup ──────────────────────────────────────────────────

// LEDC timer resolution: 16-bit gives good precision at 50Hz
// 50Hz = 20ms period. At 16-bit (65536 ticks): 1 tick ≈ 0.305 µs
#define LEDC_TIMER_BITS   LEDC_TIMER_16_BIT
#define LEDC_TIMER_MAX    65535

static void setupServoPWM() {
    // Configure LEDC timer
    ledc_timer_config_t timer_conf = {};
    timer_conf.speed_mode = LEDC_LOW_SPEED_MODE;
    timer_conf.timer_num = LEDC_TIMER_0;
    timer_conf.duty_resolution = LEDC_TIMER_BITS;
    timer_conf.freq_hz = SERVO_FREQ_HZ;
    timer_conf.clk_cfg = LEDC_AUTO_CLK;
    ledc_timer_config(&timer_conf);

    // Configure each servo channel
    for (int i = 0; i < 6; i++) {
        ledc_channel_config_t ch_conf = {};
        ch_conf.speed_mode = LEDC_LOW_SPEED_MODE;
        ch_conf.channel = (ledc_channel_t)i;
        ch_conf.timer_sel = LEDC_TIMER_0;
        ch_conf.intr_type = LEDC_INTR_DISABLE;
        ch_conf.gpio_num = servoPins[i];
        ch_conf.duty = 0;
        ch_conf.hpoint = 0;
        ledc_channel_config(&ch_conf);
    }
}

// Convert microseconds to LEDC duty value
static uint32_t usToDuty(int us) {
    // duty = (us / period_us) * max_duty
    // period_us = 1000000 / 50 = 20000
    return (uint32_t)((float)us / 20000.0f * (float)LEDC_TIMER_MAX);
}

// Set servo pulse width in microseconds
static void setServoPulse(int channel, int us) {
    if (us < SERVO_MIN_US) us = SERVO_MIN_US;
    if (us > SERVO_MAX_US) us = SERVO_MAX_US;
    ledc_set_duty(LEDC_LOW_SPEED_MODE, (ledc_channel_t)channel, usToDuty(us));
    ledc_update_duty(LEDC_LOW_SPEED_MODE, (ledc_channel_t)channel);
}

// ── Apply Motion Values ──────────────────────────────────────────────

static void applyMotionValues(float position[6]) {
    float angles[6];
    calculateAllServoAngles(position, &stewartConfig, angles);

    for (int i = 0; i < 6; i++) {
        int pulse;
        if (servoInverted[i]) {
            pulse = servoCenter[i] + (int)(angles[i] * servoPulsePerRad);
        } else {
            pulse = servoCenter[i] - (int)(angles[i] * servoPulsePerRad);
        }
        setServoPulse(i, pulse);
    }
}

// ── Forward Declarations ─────────────────────────────────────────────
void process_data(char* data);
void process_binary_packet(const uint8_t* payload);
void processIncomingByte(const uint8_t inByte);
void InterfaceMonitorTask(void* pvParameters);

// ── Binary Packet Protocol ───────────────────────────────────────────
// Same 15-byte framed packet as main controller:
// [0xAA][0x55][ch0_lo][ch0_hi][ch1_lo]...[ch5_hi][xor_checksum]  (little-endian)

void process_binary_packet(const uint8_t* payload) {
    float raw[6];
    for (int i = 0; i < 6; i++) {
        uint16_t val = (uint16_t)payload[i * 2] | ((uint16_t)payload[i * 2 + 1] << 8);
        raw[i] = (float)val;
    }

    float position[6];
    mapRawToPosition(raw, &axisScales, maxRawInput, position);

    if (xSemaphoreTake(xMutex, pdMS_TO_TICKS(5)) == pdTRUE) {
        for (int i = 0; i < 6; i++) arr[i] = position[i];
        applyMotionValues(position);
        xSemaphoreGive(xMutex);
    }
}

// ── Legacy CSV Parser + Command Handler ──────────────────────────────

void process_data(char* data) {
    // ── DBG:1 / DBG:0 — Toggle verbose debug output ─────────────────
    if (strcmp(data, DEBUG_ENABLE_CMD) == 0) {
        debugEnabled = true;
        serial_printf("Debug output enabled\r\n");
        return;
    } else if (strcmp(data, DEBUG_DISABLE_CMD) == 0) {
        debugEnabled = false;
        serial_printf("Debug output disabled\r\n");
        return;
    }

    // ── SCALE? — Query current axis scaling factors ──────────────────
    if (strcmp(data, "SCALE?") == 0) {
        serial_printf("SCALE:%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\r\n",
            axisScales.scale[0], axisScales.scale[1], axisScales.scale[2],
            axisScales.scale[3], axisScales.scale[4], axisScales.scale[5]);
        return;
    }

    // ── BITS:N — Set input bit depth ─────────────────────────────────
    if (strncmp(data, "BITS:", 5) == 0) {
        int bits = atoi(data + 5);
        if (bits >= 8 && bits <= 16) {
            inputBitRange = (uint8_t)bits;
            maxRawInput = (float)((1 << bits) - 1);
            serial_printf("BITS:%d,max_raw=%.0f\r\n", inputBitRange, maxRawInput);
        } else {
            serial_printf("ERR:BITS range 8-16\r\n");
        }
        return;
    }

    // ── BITS? — Query current input bit depth ────────────────────────
    if (strcmp(data, "BITS?") == 0) {
        serial_printf("BITS:%d,max_raw=%.0f\r\n", inputBitRange, maxRawInput);
        return;
    }

    // ── VERSION? — Report firmware version + protocol version ────────
    if (strcmp(data, "VERSION?") == 0) {
        serial_printf("VERSION:%s,proto=%d,platform=%s,date=%s,time=%s\r\n",
            FW_VERSION_STRING, FW_PROTOCOL_VERSION, FW_PLATFORM_ID,
            FW_BUILD_DATE, FW_BUILD_TIME);
        return;
    }

    // ── FINGERPRINT? — Unique device identity for handshake ──────────
    if (strcmp(data, "FINGERPRINT?") == 0) {
        uint8_t mac[6];
        esp_efuse_mac_get_default(mac);
        serial_printf("FINGERPRINT:%02X%02X%02X%02X%02X%02X,fw=%s,proto=%d,platform=%s\r\n",
            mac[0], mac[1], mac[2], mac[3], mac[4], mac[5],
            FW_VERSION_STRING, FW_PROTOCOL_VERSION, FW_PLATFORM_ID);
        return;
    }

    // ── CONFIG? — Query full platform geometry ───────────────────────
    if (strcmp(data, "CONFIG?") == 0) {
        serial_printf("CONFIG:RD=%.2f,PD=%.2f,L1=%.2f,L2=%.2f,height=%.2f,theta_r=%.2f,theta_p=%.2f\r\n",
            stewartConfig.RD, stewartConfig.PD,
            stewartConfig.ServoArmLengthL1, stewartConfig.ConnectingArmLengthL2,
            stewartConfig.platformHeight, stewartConfig.theta_r, stewartConfig.theta_p);
        serial_printf("SERVO:center=%d,%d,%d,%d,%d,%d,pulse_per_rad=%.1f\r\n",
            servoCenter[0], servoCenter[1], servoCenter[2],
            servoCenter[3], servoCenter[4], servoCenter[5], servoPulsePerRad);
        return;
    }

    // ── CONFIG:key=value — Set platform geometry parameter ───────────
    if (strncmp(data, "CONFIG:", 7) == 0) {
        char* param = data + 7;
        char* eq = strchr(param, '=');
        if (eq) {
            *eq = '\0';
            float val = atof(eq + 1);
            bool changed = true;
            if (strcmp(param, "RD") == 0) stewartConfig.RD = val;
            else if (strcmp(param, "PD") == 0) stewartConfig.PD = val;
            else if (strcmp(param, "L1") == 0) stewartConfig.ServoArmLengthL1 = val;
            else if (strcmp(param, "L2") == 0) stewartConfig.ConnectingArmLengthL2 = val;
            else if (strcmp(param, "height") == 0) stewartConfig.platformHeight = val;
            else if (strcmp(param, "theta_r") == 0) stewartConfig.theta_r = val;
            else if (strcmp(param, "theta_p") == 0) stewartConfig.theta_p = val;
            else { changed = false; serial_printf("CONFIG:ERR unknown key '%s'\r\n", param); }
            if (changed) {
                computeAxisScalesFromGeometry(&axisScales, &stewartConfig, 0.90f);
                serial_printf("CONFIG:OK %s=%.4f (scales recomputed)\r\n", param, val);
            }
        }
        return;
    }

    // ── SERVO:CENTER=c0,c1,c2,c3,c4,c5 — Set servo center calibration ─
    if (strncmp(data, "SERVO:CENTER=", 13) == 0) {
        int vals[6];
        int parsed = sscanf(data + 13, "%d,%d,%d,%d,%d,%d",
            &vals[0], &vals[1], &vals[2], &vals[3], &vals[4], &vals[5]);
        if (parsed == 6) {
            for (int i = 0; i < 6; i++) {
                if (vals[i] >= SERVO_MIN_US && vals[i] <= SERVO_MAX_US) {
                    servoCenter[i] = vals[i];
                }
            }
            serial_printf("SERVO:CENTER=%d,%d,%d,%d,%d,%d\r\n",
                servoCenter[0], servoCenter[1], servoCenter[2],
                servoCenter[3], servoCenter[4], servoCenter[5]);
        } else {
            serial_printf("ERR:SERVO:CENTER needs 6 comma-separated values\r\n");
        }
        return;
    }

    // ── SERVO:PULSE=value — Set pulse-per-radian multiplier ──────────
    if (strncmp(data, "SERVO:PULSE=", 12) == 0) {
        float val = atof(data + 12);
        if (val > 0.0f && val < 10000.0f) {
            servoPulsePerRad = val;
            serial_printf("SERVO:PULSE=%.1f\r\n", servoPulsePerRad);
        } else {
            serial_printf("ERR:SERVO:PULSE out of range\r\n");
        }
        return;
    }

    // ── ESTOP:SOFT — Return all servos to center ─────────────────────
    if (strcmp(data, "ESTOP:SOFT") == 0) {
        if (xSemaphoreTake(xMutex, pdMS_TO_TICKS(10)) == pdTRUE) {
            float home[6] = {0, 0, 0, 0, 0, 0};
            for (int i = 0; i < 6; i++) arr[i] = 0;
            applyMotionValues(home);
            xSemaphoreGive(xMutex);
        }
        serial_printf("ESTOP:SOFT — Servos homing to center\r\n");
        return;
    }

    // ── ZERO — Reset to home position ────────────────────────────────
    if (strcmp(data, "ZERO") == 0) {
        if (xSemaphoreTake(xMutex, pdMS_TO_TICKS(100)) == pdTRUE) {
            float home[6] = {0, 0, 0, 0, 0, 0};
            for (int i = 0; i < 6; i++) arr[i] = 0;
            applyMotionValues(home);
            xSemaphoreGive(xMutex);
            serial_printf("ZERO:OK — All servos at center\r\n");
        } else {
            serial_printf("ZERO:ERR — Mutex timeout\r\n");
        }
        return;
    }

    // ── CSV motion data: val0,val1,val2,val3,val4,val5 ───────────────
    // Parse comma-separated raw values (legacy protocol)
    float raw[6] = {0};
    int count = 0;
    char* tok = strtok(data, ",");
    while (tok != NULL && count < 6) {
        raw[count++] = (float)atof(tok);
        tok = strtok(NULL, ",");
    }

    if (count == 6) {
        float position[6];
        mapRawToPosition(raw, &axisScales, maxRawInput, position);

        if (xSemaphoreTake(xMutex, pdMS_TO_TICKS(5)) == pdTRUE) {
            for (int i = 0; i < 6; i++) arr[i] = position[i];
            applyMotionValues(position);
            xSemaphoreGive(xMutex);
        }
    }
}

// ── Serial Byte Processing ───────────────────────────────────────────

// Binary packet state machine
static enum { WAIT_SYNC1, WAIT_SYNC2, READ_PAYLOAD } binState = WAIT_SYNC1;
static uint8_t binPayload[13]; // 12 data bytes + 1 checksum
static int binPos = 0;

void processIncomingByte(const uint8_t inByte) {
    // Binary packet detection: 0xAA 0x55 header
    switch (binState) {
        case WAIT_SYNC1:
            if (inByte == 0xAA) binState = WAIT_SYNC2;
            break;
        case WAIT_SYNC2:
            if (inByte == 0x55) { binState = READ_PAYLOAD; binPos = 0; }
            else binState = WAIT_SYNC1;
            break;
        case READ_PAYLOAD:
            binPayload[binPos++] = inByte;
            if (binPos >= 13) {
                // Verify XOR checksum
                uint8_t xorCheck = 0;
                for (int i = 0; i < 12; i++) xorCheck ^= binPayload[i];
                if (xorCheck == binPayload[12]) {
                    process_binary_packet(binPayload);
                }
                binState = WAIT_SYNC1;
            }
            return; // don't feed binary bytes to ASCII parser
    }

    // Legacy ASCII path: accumulate until 'X' terminator
    static char input_line[MAX_SERIAL_INPUT];
    static unsigned int input_pos = 0;

    if (inByte == 'X') {
        input_line[input_pos] = 0;
        process_data(input_line);
        input_pos = 0;
    } else if (inByte == '\r' || inByte == '\n') {
        // Also accept newline as terminator for command queries
        if (input_pos > 0) {
            input_line[input_pos] = 0;
            // Only process as command if it looks like a query (contains '?' or ':')
            if (strchr(input_line, '?') || strchr(input_line, ':')) {
                process_data(input_line);
            }
            input_pos = 0;
        }
    } else {
        if (input_pos < (MAX_SERIAL_INPUT - 1))
            input_line[input_pos++] = inByte;
    }
}

// ── Interface Monitor Task (Core 0: serial I/O) ─────────────────────

void InterfaceMonitorTask(void* pvParameters) {
    // Open stdin for reading
    int fd = open("/dev/console", O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        // Fallback: try stdin fd 0
        fd = STDIN_FILENO;
    }

    uint8_t buf[64];
    for (;;) {
        int n = read(fd, buf, sizeof(buf));
        if (n > 0) {
            for (int i = 0; i < n; i++) {
                processIncomingByte(buf[i]);
            }
        } else {
            vTaskDelay(pdMS_TO_TICKS(1));
        }
    }
}

// ── App Main ─────────────────────────────────────────────────────────

extern "C" void app_main(void) {
    // Initialize NVS
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        nvs_flash_init();
    }

    // Create mutexes
    xMutex = xSemaphoreCreateMutex();
    xPrintMutex = xSemaphoreCreateMutex();

    // Initialize platform config with Mini-6DOF defaults
    initMiniDefaults(&stewartConfig);

    // Compute axis scales from geometry
    computeAxisScalesFromGeometry(&axisScales, &stewartConfig, 0.90f);

    serial_printf("\r\n");
    serial_printf("╔══════════════════════════════════════════╗\r\n");
    serial_printf("║     Mini-6DOF Controller v%s          ║\r\n", FW_VERSION_STRING);
    serial_printf("║     Protocol: %d  Platform: %s   ║\r\n", FW_PROTOCOL_VERSION, FW_PLATFORM_ID);
    serial_printf("╚══════════════════════════════════════════╝\r\n");
    serial_printf("Geometry: RD=%.2f PD=%.2f L1=%.2f L2=%.2f H=%.2f\r\n",
        stewartConfig.RD, stewartConfig.PD,
        stewartConfig.ServoArmLengthL1, stewartConfig.ConnectingArmLengthL2,
        stewartConfig.platformHeight);
    serial_printf("Scales: %.1f,%.1f,%.1f,%.1f,%.1f,%.1f\r\n",
        axisScales.scale[0], axisScales.scale[1], axisScales.scale[2],
        axisScales.scale[3], axisScales.scale[4], axisScales.scale[5]);
    serial_printf("Bit depth: %d (max_raw=%.0f)\r\n", inputBitRange, maxRawInput);

    // Print fingerprint
    {
        uint8_t mac[6];
        esp_efuse_mac_get_default(mac);
        serial_printf("Fingerprint: %02X%02X%02X%02X%02X%02X\r\n",
            mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    }

    // Setup servo enable pin
    gpio_set_direction((gpio_num_t)SERVO_ENABLE_PIN, GPIO_MODE_OUTPUT);
    gpio_set_level((gpio_num_t)SERVO_ENABLE_PIN, 0); // disabled initially

    // Setup LEDC PWM for servos
    setupServoPWM();

    // Home all servos to center
    {
        float home[6] = {0, 0, 0, 0, 0, 0};
        applyMotionValues(home);
    }

    serial_printf("Servos initialized at center. Enabling power...\r\n");

    // Brief delay then enable servo power
    vTaskDelay(pdMS_TO_TICKS(500));
    gpio_set_level((gpio_num_t)SERVO_ENABLE_PIN, 1);

    serial_printf("Servo power ON. Ready for motion data.\r\n");

    // Start serial monitor task on Core 0
    xTaskCreatePinnedToCore(
        InterfaceMonitorTask,
        "SerialMonitor",
        8192,
        NULL,
        5,
        NULL,
        0
    );

    serial_printf("Serial monitor started. Accepting commands.\r\n");
    serial_printf("Commands: VERSION? FINGERPRINT? CONFIG? SCALE? BITS? BITS:N ZERO ESTOP:SOFT\r\n");

    // Main loop — currently idle (servo updates happen in response to serial data)
    // Could add watchdog, telemetry, etc. here
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}
