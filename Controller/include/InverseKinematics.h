#ifndef INVERSE_KINEMATICS_H
#define INVERSE_KINEMATICS_H

#include <math.h>

// Export macro for shared-library test builds (no-op on ESP-IDF)
#if defined(IK_BUILD_SHARED) && defined(_WIN32)
    #define IK_API __declspec(dllexport)
#else
    #define IK_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Constants for angle conversions
#define IK_DEG_TO_RAD 0.017453292519943295769236907684886
#define IK_RAD_TO_DEG 57.295779513082320876798154814105
#define IK_PI 3.14159265359

// Servo angle limits (radians) — physical range of the rotary actuators
#define IK_SERVO_MIN_RAD -1.0471975512f  /* -60 degrees */
#define IK_SERVO_MAX_RAD  1.0471975512f  /*  60 degrees */

/**
 * Platform configuration structure (compact form — 3-pair symmetric topology)
 */
typedef struct {
    // Platform geometry
    float theta_r;                  // Base rotation angle in degrees
    float theta_s[6];               // Servo angles array
    float theta_p;                  // Platform rotation angle in degrees
    float RD;                       // Radius of the base
    float PD;                       // Radius of the platform
    float ServoArmLengthL1;         // Length of servo arm
    float ConnectingArmLengthL2;    // Length of connecting arm
    float platformHeight;           // Neutral height of platform

    // Drive train parameters (not used for PWM servos but kept for API compat)
    float steps_per_degree;
    float virtual_gear;
    float planetary_ratio;
    int   encoder_ppr;
} StewartConfig;

/**
 * Per-actuator definition — generalized form supporting arbitrary motor placement.
 */
typedef struct {
    float base_pos[3];      // B_k: servo shaft position on base [x,y,z] (mm)
    float plat_pos[3];      // P_k: ball joint on platform in platform frame [x,y,z] (mm)
    float beta;             // Servo axis orientation angle in base x-y plane (radians)
    float L1;               // Servo arm length (mm)
    float L2;               // Connecting rod length (mm)
} ActuatorDef;

/**
 * Full platform definition using per-actuator parameterization.
 */
typedef struct {
    ActuatorDef actuators[6];
    float       home_height;
    float       servo_min_rad;
    float       servo_max_rad;
    float       steps_per_degree;
    float       virtual_gear;
    float       planetary_ratio;
    int         encoder_ppr;
} PlatformDef;

IK_API float calculateServoAngle(int servoIndex, const float position[6], const StewartConfig* config);
IK_API void calculateAllServoAngles(const float position[6], const StewartConfig* config, float servoAngles[6]);
IK_API void initDefaultStewartConfig(StewartConfig* config);
IK_API void computeStepsPerDegree(StewartConfig* config);
IK_API int validatePosition(const float position[6], const StewartConfig* config);
IK_API void buildPlatformFromConfig(const StewartConfig* config, PlatformDef* platform);
IK_API float calcActuatorAngle(const float position[6], const ActuatorDef* act,
                               float home_h, float servo_min, float servo_max);
IK_API void calcAllActuatorAngles(const float position[6], const PlatformDef* platform,
                                  float angles[6]);
IK_API int validatePositionV2(const float position[6], const PlatformDef* platform);

#ifdef __cplusplus
}
#endif

#endif // INVERSE_KINEMATICS_H
