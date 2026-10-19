
package vn.wisense.detect.breathing;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreathingEstimatorTest {

    @Test
    void resampleKeepsLinearSignalExact() {
        double[] tSec = {0, 0.13, 0.31, 0.42, 0.77, 1.05, 1.5};
        double[][] pts = new double[tSec.length][];

        for (int i = 0; i < tSec.length; i++) {
            pts[i] = new double[]{tSec[i] * 1e6, 2 * tSec[i]};
        }

        double[] x = BreathingEstimator.resample(pts, 10.0);

        assertEquals(15, x.length);
        for (int i = 0; i < x.length; i++) {
            assertEquals(2 * 0.1 * i, x[i], 1e-9);
        }
    }

    @Test
    void meanAmpIgnoresNullSubcarriers() {
        assertEquals(20.0,
                BreathingEstimator.meanAmp(
                        new float[]{20f, 0f, 20f, 0f}),
                1e-9);
    }

    @Test
    void peakBpmFinds15() {
        double fs = 10.0;
        double[] x = new double[600];

        for (int i = 0; i < x.length; i++) {
            x[i] = 20 + 0.5
                    * Math.sin(2 * Math.PI * 0.25 * i / fs);
        }

        assertEquals(15.0, BreathingEstimator.peakBpm(x, fs), 0.5);
    }

    @Test
    void peakBpmFinds24() {
        double fs = 10.0;
        double[] x = new double[600];

        for (int i = 0; i < x.length; i++) {
            x[i] = 20 + 0.5
                    * Math.sin(2 * Math.PI * 0.4 * i / fs);
        }

        assertEquals(24.0, BreathingEstimator.peakBpm(x, fs), 0.5);
    }

    @Test
    void noisySignalAt15BpmHasSufficientSnr() {
        double fs = 10.0;
        double[] x = new double[600];
        Random random = new Random(42);

        for (int i = 0; i < x.length; i++) {
            double signal = 0.5
                    * Math.sin(2 * Math.PI * 0.25 * i / fs);
            double noise = 0.2 * random.nextGaussian();
            x[i] = 20 + signal + noise;
        }

        BreathingEstimator.SpectralEstimate result =
                BreathingEstimator.analyze(x, fs);

        assertEquals(15.0, result.bpm(), 0.5);
        assertTrue(result.snr() >= 3.0,
                "SNR phải đạt ngưỡng 3, thực tế: " + result.snr());
    }

    @Test
    void linearTrendDoesNotPullPeakTo9Bpm() {
        double fs = 10.0;
        double[] x = new double[600];

        for (int i = 0; i < x.length; i++) {
            double trend = 0.02 * i;
            double breathing = 0.5
                    * Math.sin(2 * Math.PI * 0.25 * i / fs);
            x[i] = 20 + trend + breathing;
        }

        BreathingEstimator.SpectralEstimate result =
                BreathingEstimator.analyze(x, fs);

        assertEquals(15.0, result.bpm(), 0.5);
        assertTrue(result.snr() >= 3.0);
    }

    @Test
    void peakBpmHandlesTooShortSignal() {
        assertEquals(0.0,
                BreathingEstimator.peakBpm(new double[]{1.0}, 10.0),
                0.0);
    }

    @Test
    void peakBpmUsesOnlyLast4096Samples() {
        double fs = 10.0;
        double[] x = new double[5000];

        for (int i = 0; i < x.length; i++) {
            x[i] = 20 + 0.5
                    * Math.sin(2 * Math.PI * 0.25 * i / fs);
        }

        assertEquals(15.0, BreathingEstimator.peakBpm(x, fs), 0.5);
    }

    @Test
    void configureMinSnrAcceptsPositiveFiniteValue() {
        BreathingEstimator detector = new BreathingEstimator();

        assertDoesNotThrow(
                () -> detector.configure("breathMinSnr", 4.0));
    }

    @Test
    void configureMinSnrRejectsUnsupportedKey() {
        BreathingEstimator detector = new BreathingEstimator();

        assertThrows(IllegalArgumentException.class,
                () -> detector.configure("unknownKey", 3.0));
    }

    @Test
    void configureMinSnrRejectsInvalidValues() {
        BreathingEstimator detector = new BreathingEstimator();

        assertThrows(IllegalArgumentException.class,
                () -> detector.configure("breathMinSnr", 0.0));

        assertThrows(IllegalArgumentException.class,
                () -> detector.configure("breathMinSnr", -1.0));

        assertThrows(IllegalArgumentException.class,
                () -> detector.configure("breathMinSnr", Double.NaN));

        assertThrows(IllegalArgumentException.class,
                () -> detector.configure(
                        "breathMinSnr", Double.POSITIVE_INFINITY));
    }
}
