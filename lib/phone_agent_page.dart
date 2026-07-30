import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Phone side. This talks to the native Android layer (MainActivity.kt +
/// ScreenStreamService.kt) over a MethodChannel/EventChannel. The actual
/// screen capture (MediaProjection) and input injection (AccessibilityService)
/// happen natively — Flutter here is just the control panel.
class PhoneAgentPage extends StatefulWidget {
  const PhoneAgentPage({super.key});

  @override
  State<PhoneAgentPage> createState() => _PhoneAgentPageState();
}

class _PhoneAgentPageState extends State<PhoneAgentPage> with WidgetsBindingObserver {
  static const _method = MethodChannel('phonebridge/control');
  static const _events = EventChannel('phonebridge/status');

  bool running = false;
  bool clientConnected = false;
  String ip = '...';
  int port = 8888;
  bool accessibilityEnabled = false;
  StreamSubscription? _sub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshIp();
    _refreshAccessibilityStatus();
    _sub = _events.receiveBroadcastStream().listen((event) {
      final Map map = event as Map;
      setState(() {
        running = map['running'] ?? running;
        clientConnected = map['clientConnected'] ?? clientConnected;
        accessibilityEnabled = map['accessibilityEnabled'] ?? accessibilityEnabled;
      });
    });
  }

  Future<void> _refreshIp() async {
    try {
      final result = await _method.invokeMethod<String>('getLocalIp');
      setState(() => ip = result ?? 'unknown');
    } on PlatformException {
      setState(() => ip = 'unknown');
    }
  }

  Future<void> _toggleService() async {
    try {
      if (running) {
        await _method.invokeMethod('stopService');
      } else {
        await _method.invokeMethod('startService');
      }
    } on PlatformException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Error: ${e.message}')));
      }
    }
  }

  Future<void> _openAccessibilitySettings() async {
    await _method.invokeMethod('openAccessibilitySettings');
  }

  Future<void> _refreshAccessibilityStatus() async {
    try {
      final enabled =
          await _method.invokeMethod<bool>('isAccessibilityEnabled');
      if (mounted) setState(() => accessibilityEnabled = enabled ?? false);
    } on PlatformException {
      // ignore, will retry on next resume
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Catches coming back from the Settings app after enabling
    // Accessibility, which previously only updated via a service
    // broadcast that never fired if the service wasn't running yet.
    if (state == AppLifecycleState.resumed) {
      _refreshAccessibilityStatus();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Phone Bridge — Agent')),
      body: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.circle,
                            size: 12,
                            color: running ? Colors.green : Colors.grey),
                        const SizedBox(width: 8),
                        Text(running ? 'Server running' : 'Server stopped'),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text('Connect from PC to: $ip:$port',
                        style: const TextStyle(
                            fontSize: 18, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Icon(Icons.circle,
                            size: 12,
                            color:
                                clientConnected ? Colors.green : Colors.grey),
                        const SizedBox(width: 8),
                        Text(clientConnected
                            ? 'PC client connected'
                            : 'No client connected'),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            if (!accessibilityEnabled)
              Card(
                color: Colors.orange.shade900,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Input control needs the Accessibility Service enabled once.',
                      ),
                      const SizedBox(height: 8),
                      ElevatedButton(
                        onPressed: _openAccessibilitySettings,
                        child: const Text('Enable in Settings'),
                      ),
                    ],
                  ),
                ),
              ),
            const SizedBox(height: 16),
            ElevatedButton.icon(
              onPressed: _toggleService,
              icon: Icon(running ? Icons.stop : Icons.play_arrow),
              label: Text(running ? 'Stop Bridge' : 'Start Bridge'),
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
              ),
            ),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: _refreshIp,
              child: const Text('Refresh IP'),
            ),
          ],
        ),
      ),
    );
  }
}
