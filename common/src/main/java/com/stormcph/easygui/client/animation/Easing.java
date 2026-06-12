package com.stormcph.easygui.client.animation;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Standard easing curves. Input and output are both 0..1 (BACK/ELASTIC overshoot slightly).
 */
@Environment(EnvType.CLIENT)
public enum Easing {
    LINEAR {
        @Override
        public float apply(float t) {
            return t;
        }
    },
    QUAD_IN {
        @Override
        public float apply(float t) {
            return t * t;
        }
    },
    QUAD_OUT {
        @Override
        public float apply(float t) {
            return 1 - (1 - t) * (1 - t);
        }
    },
    QUAD_IN_OUT {
        @Override
        public float apply(float t) {
            return t < 0.5f ? 2 * t * t : 1 - pow(-2 * t + 2, 2) / 2;
        }
    },
    CUBIC_IN {
        @Override
        public float apply(float t) {
            return t * t * t;
        }
    },
    CUBIC_OUT {
        @Override
        public float apply(float t) {
            return 1 - pow(1 - t, 3);
        }
    },
    CUBIC_IN_OUT {
        @Override
        public float apply(float t) {
            return t < 0.5f ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2;
        }
    },
    QUART_OUT {
        @Override
        public float apply(float t) {
            return 1 - pow(1 - t, 4);
        }
    },
    QUINT_OUT {
        @Override
        public float apply(float t) {
            return 1 - pow(1 - t, 5);
        }
    },
    SINE_IN {
        @Override
        public float apply(float t) {
            return 1 - (float) Math.cos(t * Math.PI / 2);
        }
    },
    SINE_OUT {
        @Override
        public float apply(float t) {
            return (float) Math.sin(t * Math.PI / 2);
        }
    },
    SINE_IN_OUT {
        @Override
        public float apply(float t) {
            return -((float) Math.cos(Math.PI * t) - 1) / 2;
        }
    },
    EXPO_OUT {
        @Override
        public float apply(float t) {
            return t >= 1 ? 1 : 1 - (float) Math.pow(2, -10 * t);
        }
    },
    CIRC_OUT {
        @Override
        public float apply(float t) {
            return (float) Math.sqrt(1 - pow(t - 1, 2));
        }
    },
    BACK_OUT {
        @Override
        public float apply(float t) {
            float c1 = 1.70158f;
            float c3 = c1 + 1;
            return 1 + c3 * pow(t - 1, 3) + c1 * pow(t - 1, 2);
        }
    },
    ELASTIC_OUT {
        @Override
        public float apply(float t) {
            if (t <= 0) return 0;
            if (t >= 1) return 1;
            float c4 = (float) (2 * Math.PI) / 3;
            return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4)) + 1;
        }
    },
    BOUNCE_OUT {
        @Override
        public float apply(float t) {
            float n1 = 7.5625f;
            float d1 = 2.75f;
            if (t < 1 / d1) {
                return n1 * t * t;
            } else if (t < 2 / d1) {
                t -= 1.5f / d1;
                return n1 * t * t + 0.75f;
            } else if (t < 2.5f / d1) {
                t -= 2.25f / d1;
                return n1 * t * t + 0.9375f;
            } else {
                t -= 2.625f / d1;
                return n1 * t * t + 0.984375f;
            }
        }
    };

    public abstract float apply(float t);

    /** Clamps the input to 0..1 before applying the curve. */
    public float applyClamped(float t) {
        return apply(Math.max(0f, Math.min(1f, t)));
    }

    private static float pow(float base, int exp) {
        float result = 1f;
        for (int i = 0; i < exp; i++) {
            result *= base;
        }
        return result;
    }
}
