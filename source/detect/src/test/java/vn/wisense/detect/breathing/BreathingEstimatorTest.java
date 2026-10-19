package vn.wisense.detect.breathing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BreathingEstimatorTest {

    @Test
    void resampleKeepsLinearSignalExact() {
        // thời điểm không đều (giây), giá trị y = 2*t: hàm tuyến tính nên nội suy phải ra đúng
        double[] tSec = {0, 0.13, 0.31, 0.42, 0.77, 1.05, 1.5};
        double[][] pts = new double[tSec.length][];
        for (int i = 0; i < tSec.length; i++) pts[i] = new double[]{tSec[i] * 1e6, 2 * tSec[i]};

        double[] x = BreathingEstimator.resample(pts, 10.0);

        assertEquals(15, x.length);                       // 1,5 giây × 10 Hz
        for (int i = 0; i < x.length; i++) {
            assertEquals(2 * 0.1 * i, x[i], 1e-9);        // mẫu thứ i ở t = 0,1*i giây
        }
    }

    @Test
    void meanAmpIgnoresNullSubcarriers() {
        assertEquals(20.0, BreathingEstimator.meanAmp(new float[]{20f, 0f, 20f, 0f}), 1e-9);
    }

    @Test
    void peakBpmFinds15() {
        double fs = 10.0;
        double[] x = new double[600];                         // 60 giây
        for (int i = 0; i < x.length; i++) x[i] = 20 + 0.5 * Math.sin(2 * Math.PI * 0.25 * i / fs);
        assertEquals(15.0, BreathingEstimator.peakBpm(x, fs), 0.5);
    }

    @Test
    void peakBpmFinds24() {
        double fs = 10.0;
        double[] x = new double[600];
        for (int i = 0; i < x.length; i++) x[i] = 20 + 0.5 * Math.sin(2 * Math.PI * 0.4 * i / fs);
        assertEquals(24.0, BreathingEstimator.peakBpm(x, fs), 0.5);
    }
}