import 'package:easy_whatsapp_backup/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('shows backup and extraction controls', (tester) async {
    await tester.pumpWidget(const EasyWhatsappBackupApp());

    expect(find.text('Easy Chat App Mover'), findsOneWidget);
    expect(find.text('Pick Folder to Back Up'), findsOneWidget);
    expect(find.text('Pick Restore Folder'), findsOneWidget);
    expect(find.text('Create Backup'), findsWidgets);
    expect(find.text('Guide'), findsOneWidget);
    expect(find.text('Feedback'), findsOneWidget);
    expect(find.byIcon(Icons.archive), findsOneWidget);

    await tester.drag(find.byType(ListView), const Offset(0, -900));
    await tester.pumpAndSettle();

    expect(find.text('Built by KPx'), findsOneWidget);

    await tester.drag(find.byType(ListView), const Offset(0, 900));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Guide'));
    await tester.pumpAndSettle();

    expect(find.text('User Guide'), findsOneWidget);
    expect(find.text('Before You Start'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.close));
    await tester.pumpAndSettle();

    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();

    expect(find.text('Pick Backup File'), findsOneWidget);
    expect(find.text('Restore Backup'), findsWidgets);
  });
}
