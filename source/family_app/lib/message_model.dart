import 'dart:convert';

class WiSenseMessage {
  final String type;
  final String content;
  final int timestamp;

  WiSenseMessage({required this.type, required this.content, required this.timestamp});

  factory WiSenseMessage.fromJson(String jsonString) {
    try {
      final Map<String, dynamic> data = jsonDecode(jsonString);
      return WiSenseMessage(
        type: data['type'] ?? 'UNKNOWN',
        content: data['content'] ?? '',
        timestamp: data['timestamp'] ?? DateTime.now().millisecondsSinceEpoch,
      );
    } catch (e) {
      return WiSenseMessage(type: 'RAW_TEXT', content: jsonString, timestamp: DateTime.now().millisecondsSinceEpoch);
    }
  }
}
