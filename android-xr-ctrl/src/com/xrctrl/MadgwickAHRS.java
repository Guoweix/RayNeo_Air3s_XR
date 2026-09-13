package com.xrctrl;

// Madgwick AHRS / IMU fusion (S. Madgwick, 2010), 9-axis with 6-axis fallback.
// Turns the glasses' raw acc/gyro/mag into a 3DoF orientation quaternion,
// replicating what libRayNeoXRMiniSDK.GetHeadTrackerPose() does on Linux.
class MadgwickAHRS {
    private float q0 = 1f, q1 = 0f, q2 = 0f, q3 = 0f;
    private final float beta;

    MadgwickAHRS(float beta) {
        this.beta = beta;
    }

    float[] getQuaternion() {
        return new float[]{q0, q1, q2, q3};
    }

    void reset() {
        q0 = 1f; q1 = 0f; q2 = 0f; q3 = 0f;
    }

    // gyro in rad/s, acc/mag in any consistent unit, dt in seconds
    void update(float gx, float gy, float gz,
                float ax, float ay, float az,
                float mx, float my, float mz,
                float dt) {
        if (dt <= 0f) return;
        if (mx == 0f && my == 0f && mz == 0f) {
            updateIMU(gx, gy, gz, ax, ay, az, dt);
            return;
        }

        // Rate of change of quaternion from gyroscope
        float qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz);
        float qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy);
        float qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx);
        float qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx);

        if (!(ax == 0f && ay == 0f && az == 0f)) {
            float recipNorm = invSqrt(ax * ax + ay * ay + az * az);
            ax *= recipNorm; ay *= recipNorm; az *= recipNorm;

            recipNorm = invSqrt(mx * mx + my * my + mz * mz);
            mx *= recipNorm; my *= recipNorm; mz *= recipNorm;

            float _2q0mx = 2f * q0 * mx, _2q0my = 2f * q0 * my, _2q0mz = 2f * q0 * mz;
            float _2q1mx = 2f * q1 * mx, _2q2mx = 2f * q2 * mx;
            float _2q0 = 2f * q0, _2q1 = 2f * q1;
            float _2q2 = 2f * q2, _2q3 = 2f * q3, _2q0q2 = 2f * q0 * q2, _2q2q3 = 2f * q2 * q3;
            float q0q0 = q0 * q0, q0q1 = q0 * q1, q0q2 = q0 * q2, q0q3 = q0 * q3;
            float q1q1 = q1 * q1, q1q2 = q1 * q2, q1q3 = q1 * q3;
            float q2q2 = q2 * q2, q2q3 = q2 * q3, q3q3 = q3 * q3;

            // Reference direction of Earth's magnetic field
            float hx = mx * q0q0 - _2q0my * mz + _2q0mz * my + mx * q1q1 + _2q1mx * q3 - mx * q2q2 - _2q2 * my * q3 - mx * q3q3;
            float hy = _2q0mx * mz + my * q0q0 - _2q0mz * mx + _2q1mx * q2 + my * q1q1 + _2q1 * mz * q3 - my * q2q2 + _2q2mx * q3 - my * q3q3;
            float _2bx = (float) Math.sqrt(hx * hx + hy * hy);
            float _2bz = -_2q0mx * my + _2q0my * mx + mz * q0q0 + _2q1mx * q2 + mz * q1q1 + _2q1 * my * q3 - mz * q2q2 - _2q2 * mx * q3 + mz * q3q3;
            float _4bx = 2f * _2bx;
            float _4bz = 2f * _2bz;

            // Gradient descent algorithm corrective step
            float s0 = -_2q2 * (2f * q1q3 - _2q0q2 - ax)
                    + _2q1 * (2f * q0q1 + _2q2q3 - ay)
                    - _2bz * q2 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx)
                    + (-_2bx * q3 + _2bz * q1) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my)
                    + _2bx * q2 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz);
            float s1 = _2q3 * (2f * q1q3 - _2q0q2 - ax)
                    + _2q0 * (2f * q0q1 + _2q2q3 - ay)
                    - 4f * q1 * (1f - 2f * q1q1 - 2f * q2q2 - az)
                    + _2bz * q3 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx)
                    + (_2bx * q2 + _2bz * q0) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my)
                    + (_2bx * q3 - _4bz * q1) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz);
            float s2 = -_2q0 * (2f * q1q3 - _2q0q2 - ax)
                    + _2q3 * (2f * q0q1 + _2q2q3 - ay)
                    - 4f * q2 * (1f - 2f * q1q1 - 2f * q2q2 - az)
                    + (-_4bx * q2 - _2bz * q0) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx)
                    + (_2bx * q1 + _2bz * q3) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my)
                    + (_2bx * q0 - _4bz * q2) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz);
            float s3 = _2q1 * (2f * q1q3 - _2q0q2 - ax)
                    + _2q2 * (2f * q0q1 + _2q2q3 - ay)
                    + (-_4bx * q3 + _2bz * q1) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx)
                    + (-_2bx * q0 + _2bz * q2) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my)
                    + _2bx * q1 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz);

            float rn = invSqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3);
            s0 *= rn; s1 *= rn; s2 *= rn; s3 *= rn;

            qDot1 -= beta * s0; qDot2 -= beta * s1; qDot3 -= beta * s2; qDot4 -= beta * s3;
        }

        integrate(qDot1, qDot2, qDot3, qDot4, dt);
    }

    private void updateIMU(float gx, float gy, float gz,
                           float ax, float ay, float az, float dt) {
        float qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz);
        float qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy);
        float qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx);
        float qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx);

        if (!(ax == 0f && ay == 0f && az == 0f)) {
            float recipNorm = invSqrt(ax * ax + ay * ay + az * az);
            ax *= recipNorm; ay *= recipNorm; az *= recipNorm;

            float _2q0 = 2f * q0, _2q1 = 2f * q1, _2q2 = 2f * q2, _2q3 = 2f * q3;
            float _4q0 = 4f * q0, _4q1 = 4f * q1, _4q2 = 4f * q2;
            float _8q1 = 8f * q1, _8q2 = 8f * q2;
            float q0q0 = q0 * q0, q1q1 = q1 * q1, q2q2 = q2 * q2, q3q3 = q3 * q3;

            float s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay;
            float s1 = _4q1 * q3q3 - _2q3 * ax + 4f * q0q0 * q1q1 - _2q0 * ay
                    - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az;
            float s2 = 4f * q0q0 * q2q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay
                    - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az;
            float s3 = 4f * q1q1 * q3q3 - _2q1 * ax + 4f * q2q2 * q3q3 - _2q2 * ay;

            float rn = invSqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3);
            s0 *= rn; s1 *= rn; s2 *= rn; s3 *= rn;

            qDot1 -= beta * s0; qDot2 -= beta * s1; qDot3 -= beta * s2; qDot4 -= beta * s3;
        }

        integrate(qDot1, qDot2, qDot3, qDot4, dt);
    }

    private void integrate(float qDot1, float qDot2, float qDot3, float qDot4, float dt) {
        q0 += qDot1 * dt;
        q1 += qDot2 * dt;
        q2 += qDot3 * dt;
        q3 += qDot4 * dt;
        float rn = invSqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
        q0 *= rn; q1 *= rn; q2 *= rn; q3 *= rn;
    }

    private static float invSqrt(float x) {
        return (float) (1.0 / Math.sqrt(x));
    }
}
