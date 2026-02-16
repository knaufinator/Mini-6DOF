#ifndef AXIS_SCALING_H
#define AXIS_SCALING_H

#include <math.h>
#include "InverseKinematics.h"

#ifdef __cplusplus
extern "C" {
#endif

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define AXIS_COUNT 6

/**
 * Per-axis scaling configuration.
 * Scales are derived from platform geometry via computeAxisScalesFromGeometry()
 * and represent the maximum safe displacement per axis (±scale).
 * Raw input (0 to max_raw) is mapped through mapRawToPosition() to physical position.
 *
 * scale[]    — max displacement per axis: mm for translation, degrees for rotation
 * is_angle[] — 1 if the axis output should be converted from degrees to radians
 */
typedef struct {
    float scale[AXIS_COUNT];
    int   is_angle[AXIS_COUNT];
} AxisScaleConfig;

/**
 * Initialize axis scales by probing the IK workspace.
 * Binary-searches each axis to find the max displacement where all servos
 * remain within their physical limits (±60°), then applies a safety margin.
 */
void computeAxisScalesFromGeometry(AxisScaleConfig* config, const StewartConfig* stewart, float margin);

/**
 * Map 6 raw values (0 to max_raw range) to physical position using per-axis scales.
 * Output: [surge, sway, heave, roll_rad, pitch_rad, yaw_rad]
 */
void mapRawToPosition(const float raw[6], const AxisScaleConfig* config, float max_raw, float position[6]);

#ifdef __cplusplus
}
#endif

#endif // AXIS_SCALING_H
