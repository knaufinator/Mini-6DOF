#include <sstream>
#include <Preferences.h>
#include <Bounce2.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>  
#include <ESP32Servo.h>
#include "soc/timer_group_struct.h"
#include "soc/timer_group_reg.h"

#include <BLE2902.h>
#include <cstring>
#include "freertos/FreeRTOS.h"
#include "freertos/timers.h"
#include "helpers.h"

using namespace std;

 
BLEServer* pServer = NULL;
BLECharacteristic* pPostionCharacteristic = NULL;
BLECharacteristic* pPauseCharacteristic = NULL;
  
//CPU tasks, all GPIO for motors is on 2nd CPU, this frees main CPU to process position data and talk to PC and BLE client
TaskHandle_t InterfaceMonitorTask;
TaskHandle_t GPIOLoopTask;
TimerHandle_t wtmr;

int servoEnablePin = 27;

Servo servo[6];
const int pwmMotorPins[] = {15,14,4,32,33,5};   
const  float servoPulseMult=400/(pi/4);
static int zero[6]={1500,1500,1500,1500,1500,1500};
#define MAX 2200
#define MIN 800


//max angle to allow the platform arms travel in degrees ie +-60 degrees. 
const float servo_min=radians(-60),servo_max=radians(60);
static long servo_pos[6];

//current target from pc, modified from 2 seperate tasks/cores
static volatile float arr[6]={0,0,0, 0,0,0};


//parses the data packet from the pc => x,y,z,Ry,Rx,RZ
void process_data ( char * data)
{ 
    int i = 0; 
    char *tok = strtok(data, ",");
    
    while (tok != NULL) {
      double value = (float)atof(tok);
      float temp = 0.0;
          
      if(i == 2)
        temp =mapfloat(value, 0, 4094, -4, 4);//hieve 
      else if(i > 2)//rotations, pitch,roll,yaw
        temp = mapfloat(value, 0, 4094, -10, 10) *(pi/180.0);
      else//sway,surge
        temp = mapfloat(value, 0, 4094, -7, 7); 

        arr[i++] = temp;
      
        tok = strtok(NULL, ",");
    }   

    setPos();
} 
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
    
     
    };

    void onDisconnect(BLEServer* pServer) {
        BLEDevice::startAdvertising();
    }
};

class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      std::string value = pCharacteristic->getValue();

       Serial.print(value.c_str());

      if (value.length() > 0) {
        char* c = strcpy(new char[value.length() + 1], value.c_str());
        process_data (c);          
      }
    }
};

void setPos(){  

 Serial.println("SetPos");
  
    //Platform and Base Coords
    for(int i = 0; i < 6; i++)
    {    
        long x = 0;
        float alpha = getAlpha(i,arr);

      
         if(i==INV1||i==INV2||i==INV3){
           // x = constrain(zero[i] + (alpha)*servoPulseMult, MIN,MAX);

             x = strictMap(zero[i] + (alpha)*servoPulseMult, MIN, MAX ,MIN, MAX);
          }
          else{
            //x = constrain(zero[i] - (alpha)*servoPulseMult, MIN,MAX);
          
          
              x = strictMap(zero[i] - (alpha)*servoPulseMult, MIN, MAX ,MIN, MAX);
          }
          
          servo_pos[i] = x;               
    }

    for(int i = 0; i < 6; i++)
    {
      servo[i].writeMicroseconds(servo_pos[i]);

      Serial.print(servo_pos[i]);
      Serial.print(",");
    }

    Serial.println("");
}

long strictMap(long val, long inmin, long inmax, long outmin, long outmax)
{
  if (val <= inmin) return outmin;
  if (val >= inmax) return outmax;
  return (val - inmin)*(outmax - outmin)/(inmax - inmin) + outmin;
}

//reset timer
void ping( TimerHandle_t xTimer )
{ 
    //Resets the watchdog timer in the ESP32/rtos
    //this is needed in order not have the thing reset every few seconds.
    TIMERG0.wdt_wprotect=TIMG_WDT_WKEY_VALUE;
    TIMERG0.wdt_feed=1;
    TIMERG0.wdt_wprotect=0;

    //Restart timer for this method
    xTimerStart(wtmr, 0);
}

//setup BLE access notify service and configuration characteristics 
void setupBle(){
  
  Serial.println("Starting BLE init!");

  BLEDevice::init("Mini Open 6DOF");
  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);
  pPostionCharacteristic  = pService->createCharacteristic(
                      POSITIONCHARACTERISTIC_UUID,                 
                      BLECharacteristic::PROPERTY_WRITE 
                    );
                                       
  pPostionCharacteristic->addDescriptor(new BLE2902());
  pPostionCharacteristic->setCallbacks(new MyCallbacks());
  pService->start();


  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising(); 
}

//start here
void setup(){
 
 Serial.begin(115200); 

//setup turn off pin
pinMode(servoEnablePin, OUTPUT);
digitalWrite(servoEnablePin, false);

  setupBle();

  xTaskCreatePinnedToCore(
                    InterfaceMonitorCode,   /* Task function. */
                    "InterfaceMonitor",     /* name of task. */
                    10000,       /* Stack size of task */
                    NULL,        /* parameter of the task */
                    1,           /* priority of the task */
                    &InterfaceMonitorTask,      /* Task handle to keep track of created task */
                    0);          /* pin task to core 0 */                  

   //setup the outputs for the AC servo controllers
  setupPWMpins();


  //test
// for(int i=0;i<6;i++)
//    { 
//
//   // pinMode(pwmMotorPins[i], OUTPUT);
//
//      
//    }

setPos();

delay(1000);

digitalWrite(27, true);


  //Timer for watchdog reset
  wtmr = xTimerCreate("wtmr", pdMS_TO_TICKS(1000), pdFALSE, (void*)0, reinterpret_cast<TimerCallbackFunction_t>(ping));
  xTimerStart(wtmr, 0);
}

void setupPWMpins() {
    for(int i=0;i<6;i++)
    { 
      servo[i].setPeriodHertz(50);    // standard 50 hz servo
      servo[i].attach(pwmMotorPins[i], MIN, MAX);
    }
}
  
void loop()
{      

//test
//
// for(int i=0;i<6;i++)
//{
//
//digitalWrite(pwmMotorPins[i], true);
//
//}
//
//
// delay(2000); 
// 
// for(int i=0;i<6;i++)
//{
//
//digitalWrite(pwmMotorPins[i], false);
//
//}
//
// delay(2000);
  
}

//reads the serial line,  x,y,z,RX,RY,RZX, Where X is used to indicate end of line. 
//else data will buffer and parsed once a full line is available.
void processIncomingByte (const byte inByte)
{
    static char input_line [MAX_INPUT];
    static unsigned int input_pos = 0;
  
    switch (inByte)
    {
        case 'X':   // end of text
          input_line [input_pos] = 0;  // terminating null byte
          process_data (input_line);          
          input_pos = 0;  
          break;
        case '\r':   // discard carriage return
          break;
        
        default:
          //buffer data
          if (input_pos < (MAX_INPUT - 1))
            input_line [input_pos++] = inByte;
          break;
    } 

    
} 


//Thread that handles reading from PC uart
void InterfaceMonitorCode( void * pvParameters ){
  for(;;){
    
    while (Serial.available () > 0)
      processIncomingByte (Serial.read ());
  } 
}
