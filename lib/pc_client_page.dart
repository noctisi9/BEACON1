import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';

/// PC side. Plain TCP client — no external packages needed.
///
/// Wire protocol (deliberately simple, optimize later):
///  Phone -> PC:  repeating [4-byte big-endian length][JPEG bytes]
///  PC -> Phone:  newline-delimited JSON, e.g.
///                {"type":"tap","x":123,"y":456}
///                {"type":"swipe","x1":10,"y1":10,"x2":200,"y2":10,"ms":250}
///                {"type":"back"} / {"type":"home"} / {"type":"recents"}
class PcClientPage extends StatefulWidget {
  const PcClientPage({super.key});

  @override
  State<PcClientPage> createState() => _PcClientPageState();
}

class _PcClientPageState extends State<PcClientPage> {
  final _ipController = TextEditingController(text: '192.168.43.1');
  final _portController = TextEditingController(text: '8888');

  Socket? _socket;
  Uint8List? _lastFrame;
  bool _connected = false;
  String _status = 'Disconnected';

  // Frame reassembly state
  final BytesBuilder _buffer = BytesBuilder(copy: false);
  int? _expectedLength;

  // Remembers the rendered image size so we can map tap coords back
  // to the phone's real screen resolution. Sent once by the phone as
  // the first control-channel message after connect (see below), or
  // you can hardcode your phone's resolution here as a fallback.
  Size _phoneScreenSize = const Size(1080, 2400);

  Future<void> _connect() async {
    setState(() => _status = 'Connecting...');
    try {
      final socket = await Socket.connect(
        _ipController.text.trim(),
        int.parse(_portController.text.trim()),
        timeout: const Duration(seconds: 5),
      );
      _socket = socket;
      setState(() {
        _connected = true;
        _status = 'Connected to ${_ipController.text}';
      });

      socket.listen(
        _onData,
        onError: (e) => _onDisconnect('Error: $e'),
        onDone: () => _onDisconnect('Connection closed'),
        cancelOnError: true,
      );
    } catch (e) {
      setState(() => _status = 'Failed to connect: $e');
    }
  }

  void _onDisconnect(String reason) {
    setState(() {
      _connected = false;
      _status = reason;
    });
    _socket?.destroy();
    _socket = null;
  }

  void _onData(Uint8List chunk) {
    _buffer.add(chunk);
    _drainBuffer();
  }

  void _drainBuffer() {
    var bytes = _buffer.takeBytes();
    _buffer.add(bytes); // put back; we consume via index below
    var data = _buffer.takeBytes();
    int offset = 0;

    while (true) {
      if (_expectedLength == null) {
        if (data.length - offset < 4) break;
        final bd = ByteData.sublistView(data, offset, offset + 4);
        _expectedLength = bd.getUint32(0, Endian.big);
        offset += 4;
      }
      final need = _expectedLength!;
      if (data.length - offset < need) break;
      final frame = data.sublist(offset, offset + need);
      offset += need;
      _expectedLength = null;
      setState(() => _lastFrame = frame);
    }

    if (offset < data.length) {
      _buffer.add(data.sublist(offset));
    }
  }

  void _send(Map<String, dynamic> msg) {
    if (_socket == null) return;
    _socket!.write('${jsonEncode(msg)}\n');
  }

  void _handleTapUp(TapUpDetails details, Size renderedSize) {
    final scaleX = _phoneScreenSize.width / renderedSize.width;
    final scaleY = _phoneScreenSize.height / renderedSize.height;
    final x = (details.localPosition.dx * scaleX).round();
    final y = (details.localPosition.dy * scaleY).round();
    _send({'type': 'tap', 'x': x, 'y': y});
  }

  Offset? _dragStart;

  void _handlePanStart(DragStartDetails d) => _dragStart = d.localPosition;

  void _handlePanEnd(DragEndDetails d, Size renderedSize) {
    if (_dragStart == null) return;
    final scaleX = _phoneScreenSize.width / renderedSize.width;
    final scaleY = _phoneScreenSize.height / renderedSize.height;
    // DragEndDetails has no position, so this simple version only
    // sends the start point; swap to onPanUpdate tracking if you
    // want true swipe end coordinates.
    _send({
      'type': 'swipe',
      'x1': (_dragStart!.dx * scaleX).round(),
      'y1': (_dragStart!.dy * scaleY).round(),
      'x2': (_dragStart!.dx * scaleX).round(),
      'y2': (_dragStart!.dy * scaleY).round(),
      'ms': 200,
    });
    _dragStart = null;
  }

  @override
  void dispose() {
    _socket?.destroy();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Phone Bridge — PC Client')),
      body: Row(
        children: [
          Container(
            width: 260,
            padding: const EdgeInsets.all(16),
            color: Colors.black26,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextField(
                  controller: _ipController,
                  decoration: const InputDecoration(labelText: 'Phone IP'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _portController,
                  decoration: const InputDecoration(labelText: 'Port'),
                ),
                const SizedBox(height: 16),
                ElevatedButton(
                  onPressed: _connected ? null : _connect,
                  child: const Text('Connect'),
                ),
                const SizedBox(height: 8),
                Text(_status, style: const TextStyle(fontSize: 12)),
                const Spacer(),
                Row(
                  children: [
                    IconButton(
                      onPressed: () => _send({'type': 'back'}),
                      icon: const Icon(Icons.arrow_back),
                    ),
                    IconButton(
                      onPressed: () => _send({'type': 'home'}),
                      icon: const Icon(Icons.circle_outlined),
                    ),
                    IconButton(
                      onPressed: () => _send({'type': 'recents'}),
                      icon: const Icon(Icons.square_outlined),
                    ),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child: Center(
              child: _lastFrame == null
                  ? const Text('Waiting for frames...')
                  : LayoutBuilder(builder: (context, constraints) {
                      final renderedSize = Size(
                        constraints.maxWidth,
                        constraints.maxHeight,
                      );
                      return GestureDetector(
                        onTapUp: (d) => _handleTapUp(d, renderedSize),
                        onPanStart: _handlePanStart,
                        onPanEnd: (d) => _handlePanEnd(d, renderedSize),
                        child: Image.memory(
                          _lastFrame!,
                          gaplessPlayback: true,
                          fit: BoxFit.contain,
                        ),
                      );
                    }),
            ),
          ),
        ],
      ),
    );
  }
}
