import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'notification_service.dart';
import 'message_model.dart';

class TcpService {
  Socket? _socket;
  final StreamController<String> _messageController = StreamController.broadcast();
  final StreamController<bool> _connectionStateController = StreamController.broadcast();

  Stream<String> get messageStream => _messageController.stream;
  Stream<bool> get connectionStateStream => _connectionStateController.stream;

  bool _isAlerting = false;

  Future<void> connectToServer(String ip, int port) async {
    try {
      _socket = await Socket.connect(ip, port, timeout: const Duration(seconds: 5));
      _connectionStateController.add(true);
      print("Đã kết nối TCP tới Server: $ip:$port");

      _socket!.listen(
        (List<int> data) {
          final String message = utf8.decode(data);
          _messageController.add(message.trim());
          _checkAndTriggerNotification(message.trim());
        },
        onError: (error) {
          print("Lỗi TCP Socket: $error");
          disconnect();
        },
        onDone: () {
          print("Server đã đóng kết nối.");
          disconnect();
        },
      );
    } catch (e) {
      print("Không thể kết nối: $e");
      _connectionStateController.add(false);
    }
  }

  void _checkAndTriggerNotification(String rawMsg) {
    final msg = WiSenseMessage.fromJson(rawMsg);
    if ((msg.type == "FALL_ALERT" || rawMsg.contains("FALL")) && !_isAlerting) {
      _isAlerting = true;
      NotificationService().showFallAlert(
        "🚨 BÁO ĐỘNG KHẨN CẤP", 
        "Phát hiện người nhà có dấu hiệu bị ngã. Vui lòng gọi điện hoặc kiểm tra camera ngay lập tức!"
      );
      Future.delayed(const Duration(seconds: 5), () {
        _isAlerting = false;
      });
    }
  }

  void sendMessage(String msg) {
    if (_socket != null) {
      _socket!.write(msg + "\n");
    }
  }

  void disconnect() {
    _socket?.close();
    _socket = null;
    _connectionStateController.add(false);
  }

  void dispose() {
    _messageController.close();
    _connectionStateController.add(false);
  }
}
