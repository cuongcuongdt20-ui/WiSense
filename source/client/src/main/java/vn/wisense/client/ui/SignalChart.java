package vn.wisense.client.ui;

import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;

/** Biên độ trung bình các sóng mang con theo thời gian, 30 giây gần nhất. */
public class SignalChart {
    private static final double WINDOW_S = 30;
    private final NumberAxis x = new NumberAxis();
    private final NumberAxis y = new NumberAxis();
    private final LineChart<Number, Number> chart = new LineChart<>(x, y);
    private final XYChart.Series<Number, Number> series = new XYChart.Series<>();
    private final long t0 = System.nanoTime();
    private double lastT = -1;

    public SignalChart() {
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.setTitle("Biên độ trung bình, 30 giây gần nhất");
        x.setAutoRanging(false);
        x.setTickUnit(5);
        x.setLabel("giây");
        y.setForceZeroInRange(false);
        chart.getData().add(series);
    }

    public Node node() {
        return chart;
    }

    public void add(float[] amp) {
        if (amp.length == 0) return;
        double t = (System.nanoTime() - t0) / 1e9;
        if (t - lastT < 0.05) return;                       // tối đa 20 điểm/giây
        lastT = t;
        double sum = 0;
        for (float a : amp) sum += a;
        series.getData().add(new XYChart.Data<>(t, sum / amp.length));
        while (series.getData().get(0).getXValue().doubleValue() < t - WINDOW_S) {
            series.getData().remove(0);
        }
        x.setLowerBound(t - WINDOW_S);
        x.setUpperBound(t);
    }
}
