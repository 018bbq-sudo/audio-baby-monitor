package com.example.audiobabymonitor;

final class ImaAdpcm {
    private static final int[] INDEX_TABLE = {
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
    };

    private static final int[] STEP_TABLE = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544,
            598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707,
            1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
            5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635,
            13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    private ImaAdpcm() {}

    static byte[] encode(short[] samples, int count) {
        if (count < 1) return new byte[0];
        byte[] output = new byte[4 + count / 2];
        int predictor = samples[0];
        int index = 0;
        output[0] = (byte) predictor;
        output[1] = (byte) (predictor >> 8);
        output[2] = (byte) index;
        output[3] = 0;

        int out = 4;
        int packed = 0;
        boolean lowNibble = true;
        for (int i = 1; i < count; i++) {
            int step = STEP_TABLE[index];
            int difference = samples[i] - predictor;
            int code = 0;
            if (difference < 0) {
                code = 8;
                difference = -difference;
            }
            int delta = step >> 3;
            if (difference >= step) {
                code |= 4;
                difference -= step;
                delta += step;
            }
            if (difference >= (step >> 1)) {
                code |= 2;
                difference -= step >> 1;
                delta += step >> 1;
            }
            if (difference >= (step >> 2)) {
                code |= 1;
                delta += step >> 2;
            }
            predictor += (code & 8) != 0 ? -delta : delta;
            predictor = clamp(predictor, -32768, 32767);
            index = clamp(index + INDEX_TABLE[code], 0, 88);

            if (lowNibble) {
                packed = code & 0x0f;
                lowNibble = false;
            } else {
                output[out++] = (byte) (packed | ((code & 0x0f) << 4));
                lowNibble = true;
            }
        }
        if (!lowNibble) output[out] = (byte) packed;
        return output;
    }

    static short[] decode(byte[] input) {
        if (input.length < 5) return new short[0];
        int predictor = (short) ((input[0] & 0xff) | (input[1] << 8));
        int index = clamp(input[2] & 0xff, 0, 88);
        int sampleCount = 1 + (input.length - 4) * 2;
        short[] output = new short[sampleCount];
        output[0] = (short) predictor;
        int out = 1;

        for (int i = 4; i < input.length; i++) {
            int value = input[i] & 0xff;
            for (int half = 0; half < 2; half++) {
                int code = half == 0 ? value & 0x0f : value >> 4;
                int step = STEP_TABLE[index];
                int delta = step >> 3;
                if ((code & 4) != 0) delta += step;
                if ((code & 2) != 0) delta += step >> 1;
                if ((code & 1) != 0) delta += step >> 2;
                predictor += (code & 8) != 0 ? -delta : delta;
                predictor = clamp(predictor, -32768, 32767);
                index = clamp(index + INDEX_TABLE[code], 0, 88);
                output[out++] = (short) predictor;
            }
        }
        return output;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
