import com.solutera.dilinkinspector.ScanEngine;
public class DiagnosticRulesTest {
 public static void main(String[] args) {
  check(ScanEngine.rank("com.android.settings/DevelopmentSettingsDashboardActivity")==3,"developer priority");
  check(ScanEngine.rank("com.vendor/UsbSettingsActivity")==2,"USB priority");
  check(ScanEngine.relevant("com.byd.settings"),"BYD discovery");
  check(ScanEngine.risky("com.settings.FactoryResetActivity"),"reset excluded");
  check(ScanEngine.risky("com.vendor.CalibrationActivity"),"calibration excluded");
  check(!ScanEngine.risky("com.android.settings.DevelopmentSettingsActivity"),"developer launch permitted");
  String s=ScanEngine.redact("adb_enabled=1\nserialno=ABC123\nMAC 01:23:45:67:89:ab\nowner=alice@example.org\n");
  check(s.contains("adb_enabled=1"),"useful evidence preserved");
  check(!s.contains("ABC123")&&!s.contains("01:23:45:67:89:ab")&&!s.contains("alice@example.org"),"identifiers masked");
  System.out.println("PASS: 8 diagnostic rule assertions");
 }
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
}
