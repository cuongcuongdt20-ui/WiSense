import 'package:flutter/material.dart';
import 'tcp_service.dart';
import 'message_model.dart';
import 'notification_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await NotificationService().init();
  
  runApp(const MaterialApp(
    home: WiSenseApp(),
    debugShowCheckedModeBanner: false,
  ));
}

class WiSenseApp extends StatefulWidget {
  const WiSenseApp({Key? key}) : super(key: key);

  @override
  _WiSenseAppState createState() => _WiSenseAppState();
}

class _WiSenseAppState extends State<WiSenseApp> {
  final TcpService _tcpService = TcpService();
  final TextEditingController _ipController = TextEditingController(text: "192.168.1.100");
  final TextEditingController _portController = TextEditingController(text: "8080");

  bool isConnected = false;
  bool isFalling = false;
  String latestStatus = "Đang chờ kết nối...";

  @override
  void initState() {
    super.initState();
    _tcpService.connectionStateStream.listen((state) {
      setState(() {
        isConnected = state;
        if (!state) {
            isFalling = false;
            latestStatus = "Đã ngắt kết nối";
        } else {
            latestStatus = "Đang giám sát an toàn ở nhà...";
        }
      });
    });

    _tcpService.messageStream.listen((rawMsg) {
      final msg = WiSenseMessage.fromJson(rawMsg);
      
      setState(() {
        if (msg.type == "FALL_ALERT" || rawMsg.contains("FALL")) {
          isFalling = true;
          latestStatus = "CẢNH BÁO: PHÁT HIỆN NGƯỜI THÂN BỊ NGÃ!";
        } else if (msg.type == "NORMAL" || rawMsg.contains("NORMAL")) {
          isFalling = false;
          latestStatus = "Mọi thứ đang bình thường";
        } else {
          // Xử lý các thông tin trạng thái khác
        }
      });
    });
  }

  @override
  void dispose() {
    _tcpService.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("WiSense Family Care"),
        backgroundColor: isFalling ? Colors.red : Colors.blueAccent,
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          children: [
            Row(
              children: [
                Expanded(child: TextField(controller: _ipController, decoration: const InputDecoration(labelText: "Server IP ở nhà"))),
                const SizedBox(width: 10),
                SizedBox(width: 80, child: TextField(controller: _portController, decoration: const InputDecoration(labelText: "Port"))),
                const SizedBox(width: 10),
                ElevatedButton(
                  onPressed: isConnected 
                    ? () => _tcpService.disconnect() 
                    : () => _tcpService.connectToServer(_ipController.text, int.parse(_portController.text)),
                  style: ElevatedButton.styleFrom(backgroundColor: isConnected ? Colors.red : Colors.green),
                  child: Text(isConnected ? "Ngừng xem" : "Bắt đầu xem"),
                )
              ],
            ),
            const SizedBox(height: 30),
            Container(
              height: 200,
              width: double.infinity,
              decoration: BoxDecoration(
                color: isFalling ? Colors.redAccent : (isConnected ? Colors.green.shade100 : Colors.grey.shade300),
                borderRadius: BorderRadius.circular(15),
                border: Border.all(color: isFalling ? Colors.red : Colors.transparent, width: 3),
              ),
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Text(
                    latestStatus,
                    style: TextStyle(
                      fontSize: 22, 
                      fontWeight: FontWeight.bold,
                      color: isFalling ? Colors.white : Colors.black87,
                    ),
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 30),
            const Expanded(
              child: Card(
                child: Center(child: Text("Khu vực đồ thị tín hiệu hoặc Camera (nếu có)")),
              ),
            ),
            ElevatedButton.icon(
              icon: const Icon(Icons.notifications_active),
              label: const Text("Thử test âm báo ngã"),
              onPressed: () {
                NotificationService().showFallAlert("🚨 BÁO ĐỘNG KHẨN CẤP", "Phát hiện người nhà có dấu hiệu bị ngã. Vui lòng gọi điện hoặc kiểm tra camera ngay lập tức!");
              },
            )
          ],
        ),
      ),
    );
  }
}
