import 'dart:io';
import 'package:flutter/material.dart';
import 'phone_agent_page.dart';
import 'pc_client_page.dart';

void main() {
  runApp(const PhoneBridgeApp());
}

class PhoneBridgeApp extends StatelessWidget {
  const PhoneBridgeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Phone Bridge',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.indigo,
        brightness: Brightness.dark,
      ),
      home: Platform.isAndroid ? const PhoneAgentPage() : const PcClientPage(),
    );
  }
}
