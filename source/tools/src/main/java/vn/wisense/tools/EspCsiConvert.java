package vn.wisense.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import vn.wisense.common.protocol.CsiCsv;

/**
 * Đổi file CSV dạng CSI_DATA của esp-csi (ví dụ bộ dữ liệu công khai WiFall, thu bằng ESP32-S3) sang CSV của
 * WiSense để phát lại bằng ReplayMain: sensorId,seq,timestampUs,rssi,imag0,real0,...
 *
 * <p>Nhiều file (mỗi file một đoạn ngắn quanh một hành động) được ghép thành một luồng liên tục theo thứ tự tên file:
 * seq đánh lại từ 0, thời điểm nối tiếp nhau (bỏ khoảng trống giữa các đoạn, xử lí bộ đếm µs 32 bit của ESP32 bị tràn).
 * Đầu mỗi đoạn ghi một dòng "# MARK n nhan seq=... tsUs=..." với nhãn lấy từ tên thư mục (fall, sit, walk...),
 * để biết sự việc thật xảy ra ở đâu khi đánh giá. ReplayMain bỏ qua các dòng bắt đầu bằng #.
 *
 * <pre>
 * java -cp wisense-tools.jar vn.wisense.tools.EspCsiConvert --in=WiFall/ID0 --out=data/wifall_id0.csv
 * java -cp wisense-tools.jar vn.wisense.tools.EspCsiConvert --in=WiFall/ID3/fall --out=data/wifall_id3_fall.csv --limit=10
 * </pre>
 */
public final class EspCsiConvert {
    /** Khoảng cách chèn giữa hai đoạn khi ghép (µs), bằng một chu kì gói khoảng 83 Hz. */
    static final long GAP_US = 12_000;

    private EspCsiConvert() {}

    record Stats(int files, long rows, long skipped) {}

    public static void main(String[] args) throws IOException {
        Path in = Path.of(arg(args, "--in", ""));
        Path out = Path.of(arg(args, "--out", ""));
        int sensor = Integer.parseInt(arg(args, "--sensor", "1"));
        int limit = Integer.parseInt(arg(args, "--limit", "0"));
        if (in.toString().isBlank() || out.toString().isBlank()) throw new IllegalArgumentException("Can --in va --out");

        List<Path> files;
        try (Stream<Path> s = Files.isDirectory(in) ? Files.walk(in) : Stream.of(in)) {
            files = s.filter(p -> p.toString().toLowerCase().endsWith(".csv")).sorted().toList();
        }
        if (limit > 0 && files.size() > limit) files = files.subList(0, limit);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("# WiSense CSI chuyen tu esp-csi CSI_DATA: " + in + " (" + files.size() + " file)\n");
            Stats st = convert(files, w, sensor);
            System.out.printf("Xong: %d file, %d dong, bo %d dong hong -> %s%n", st.files(), st.rows(), st.skipped(), out);
        }
    }

    /** Ghép các file theo thứ tự vào w. */
    static Stats convert(List<Path> files, BufferedWriter w, int sensor) throws IOException {
        long seq = 0, skipped = 0, nextTs = 0;
        int n = -1;
        for (int fi = 0; fi < files.size(); fi++) {
            Path f = files.get(fi);
            String label = f.getParent() == null ? "?" : f.getParent().getFileName().toString().toLowerCase();
            w.write(String.format("# MARK %d %s seq=%d tsUs=%d file=%s%n", fi + 1, label, seq, nextTs, f.getFileName()));
            long base = -1, prevRaw = -1, unwrap = 0, lastTs = nextTs;
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                Map<String, Integer> col = header(r.readLine());
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) continue;                // WiFall kết thúc dòng bằng \r\r\n
                    Row row = parse(line, col);
                    if (row == null) {
                        skipped++;
                        continue;
                    }
                    if (prevRaw >= 0 && row.localTs < prevRaw) unwrap += 1L << 32;   // bộ đếm µs của ESP32 tràn
                    prevRaw = row.localTs;
                    long raw = row.localTs + unwrap;
                    if (base < 0) base = raw;
                    long ts = nextTs + (raw - base);
                    if (n < 0) {
                        n = row.csi.length / 2;
                        w.write(CsiCsv.header(n));
                    }
                    if (row.csi.length != 2 * n) {
                        skipped++;
                        continue;
                    }
                    StringBuilder sb = new StringBuilder(32 + 8 * row.csi.length);
                    sb.append(sensor).append(',').append(seq++).append(',').append(ts).append(',').append(row.rssi);
                    for (int v : row.csi) sb.append(',').append(v);
                    w.write(sb.append('\n').toString());
                    lastTs = ts;
                }
            }
            nextTs = lastTs + GAP_US;
        }
        return new Stats(files.size(), seq, skipped);
    }

    private record Row(int rssi, long localTs, int[] csi) {}

    private static Map<String, Integer> header(String line) throws IOException {
        if (line == null) throw new IOException("File rong");
        Map<String, Integer> col = new HashMap<>();
        String[] names = line.split(",");
        for (int i = 0; i < names.length; i++) col.put(names[i].trim(), i);
        for (String need : List.of("rssi", "local_timestamp", "data")) {
            if (!col.containsKey(need)) throw new IOException("Thieu cot " + need + " (khong phai dinh dang CSI_DATA cua esp-csi)");
        }
        return col;
    }

    /**
     * Một dòng CSI_DATA. Cột data nằm trong ngoặc kép "[a, b, ...]" (chứa dấu phẩy), các cột trước đó là số/chuỗi
     * thường. Trả null nếu dòng hỏng. first_word_invalid = 1 thì 4 số đầu không hợp lệ: đặt về 0 như firmware.
     */
    static Row parse(String line, Map<String, Integer> col) {
        int q = line.indexOf('"');
        if (q < 0) return null;
        String[] head = line.substring(0, q).split(",", -1);
        int lb = line.indexOf('[', q), rb = line.indexOf(']', lb + 1);
        if (lb < 0 || rb < 0) return null;
        try {
            int rssi = Integer.parseInt(head[col.get("rssi")].trim());
            long localTs = Long.parseLong(head[col.get("local_timestamp")].trim());
            String[] parts = line.substring(lb + 1, rb).split(",");
            if (parts.length < 2 || parts.length % 2 != 0) return null;
            int[] csi = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                int v = Integer.parseInt(parts[i].trim());
                csi[i] = Math.max(-128, Math.min(127, v));
            }
            Integer fw = col.get("first_word_invalid");
            if (fw != null && fw < head.length && "1".equals(head[fw].trim())) {
                for (int i = 0; i < Math.min(4, csi.length); i++) csi[i] = 0;
            }
            return new Row(rssi, localTs, csi);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Các nhãn đã ghi bằng dòng # MARK của một file đã chuyển (để công cụ đánh giá đọc lại). */
    static List<String> marks(Path converted) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(converted, StandardCharsets.UTF_8)) {
            lines.filter(l -> l.startsWith("# MARK")).forEach(out::add);
        }
        return out;
    }

    private static String arg(String[] args, String key, String fallback) {
        for (String a : args) if (a.startsWith(key + "=")) return a.substring(key.length() + 1);
        return fallback;
    }
}
