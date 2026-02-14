#ifndef DEBUG_UART_H
#define DEBUG_UART_H

#include <stdio.h>

// Runtime-toggled debug output (controlled by DBG:1 / DBG:0 serial commands)
#ifdef ENABLE_DEBUG_UART
extern bool debugEnabled;
#define DEBUG_PRINTLN(fmt, ...) do { if (debugEnabled) printf("DEBUG: " fmt "\r\n", ##__VA_ARGS__); } while(0)
#define DEBUG_PRINT(fmt, ...)   do { if (debugEnabled) printf(fmt, ##__VA_ARGS__); } while(0)
#else
#define DEBUG_PRINTLN(fmt, ...) ((void)0)
#define DEBUG_PRINT(fmt, ...)   ((void)0)
#endif

#endif // DEBUG_UART_H
