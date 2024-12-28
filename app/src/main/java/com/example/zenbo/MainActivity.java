package com.example.zenbo;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.asus.robotframework.API.RobotAPI;
import com.asus.robotframework.API.RobotCallback;
import com.asus.robotframework.API.RobotCmdState;
import com.asus.robotframework.API.RobotErrorCode;
import com.asus.robotframework.API.RobotFace;
import com.asus.robotframework.API.Utility;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends RobotActivity {
    private TextToSpeech textToSpeech;
    private static final int PERMISSION_REQUEST_CODE = 1;
    public static MainActivity instance;
    private static final int REQUEST_CODE_SPEECH_INPUT = 1;
    private Handler handler = new Handler();
    private TextView txt;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothAdapter bluetoothAdapter;

    // 商品和beacon的對應關係
    private static final Map<String, String> PRODUCT_BEACON_MAP = new HashMap<String, String>() {{
        put("牛奶", "12:3B:6A:1A:D2:1E");
        put("麵包", "12:3B:6A:1A:D2:21");
        put("橘子", "12:3B:6A:1A:D1:DB");
    }};

    public static RobotCallback robotCallback = new RobotCallback() {
        private boolean hasWelcomed = false;

        @Override
        public void onResult(int cmd, int serial, RobotErrorCode err_code, Bundle result) {
            super.onResult(cmd, serial, err_code, result);
        }

        @Override
        public void onStateChange(int cmd, int serial, RobotErrorCode err_code, RobotCmdState state) {
            super.onStateChange(cmd, serial, err_code, state);
        }

        @Override
        public void initComplete() {
            super.initComplete();
            if (!hasWelcomed) {
                hasWelcomed = true;
                MainActivity.instance.robotAPI.robot.speak("歡迎光臨，請說出需要的商品名稱");
                new Handler().postDelayed(() -> {
                    MainActivity.instance.startSpeechRecognition();
                }, 8000);
                MainActivity.instance.checkDatabaseConnection();
            }
        }
    };

    public static RobotCallback.Listen robotListenCallback = new RobotCallback.Listen() {
        @Override
        public void onFinishRegister() {}

        @Override
        public void onVoiceDetect(JSONObject jsonObject) {}

        @Override
        public void onSpeakComplete(String s, String s1) {}

        @Override
        public void onEventUserUtterance(JSONObject jsonObject) {
            try {
                String utterance = jsonObject.getString("text");
                Log.v("Voice Input", "User said: " + utterance);
                MainActivity.instance.fetchProductDetails(utterance);
            } catch (Exception e) {
                Log.e("VoiceRecognition", e.toString());
            }
        }

        @Override
        public void onResult(JSONObject jsonObject) {}

        @Override
        public void onRetry(JSONObject jsonObject) {}
    };

    public MainActivity() {
        super(robotCallback, robotListenCallback);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (instance == null) {
            instance = this;
        }

        txt = findViewById(R.id.txt);
        this.robotAPI = new RobotAPI(getApplicationContext(), robotCallback);

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.US);
            }
        });
        checkPermissions();
//        startSpeechRecognition();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device.", Toast.LENGTH_SHORT).show();
            finish();
        }
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Toast.makeText(this, "BLE Scanner is not available.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSION_REQUEST_CODE);
        }
    }

    private void startSpeechRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "請說出商品名稱");

        try {
            startActivityForResult(intent, REQUEST_CODE_SPEECH_INPUT);
        } catch (Exception e) {
            speakOut("語音識別啟動失敗");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                String spokenText = result.get(0);
                speakOut("您選擇的商品是 " + spokenText);
                fetchProductDetails(spokenText);
            }
        }
    }

    public void fetchProductDetails(String productName) {
        String url = "http://192.168.0.105/get_product.php?product_name=" + productName;

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(url)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    String errorMessage = "網絡請求失敗";
                    txt.setText(errorMessage);
                    speakOut(errorMessage);
                });
                Log.e("HTTP_ERROR", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseData = response.body().string();

                    runOnUiThread(() -> {
                        try {
                            JSONObject jsonObject = new JSONObject(responseData);

                            if (jsonObject.has("error")) {
                                String error = jsonObject.getString("error");
                                txt.setText(error);
                                speakOut(error);
                            } else {
                                String name = jsonObject.getString("name");
                                double price = jsonObject.getDouble("price");
                                int stock = jsonObject.getInt("stock");
                                String location = jsonObject.getString("location");

                                String productInfo = String.format(
                                        "商品名稱：%s\n價格：%.2f\n庫存：%d\n位置：%s",
                                        name, price, stock, location
                                );
                                txt.setText(productInfo);

                                String speechInfo = String.format(
                                        "商品%s的資訊已找到，價格是%.2f元，庫存有%d件，我現在帶您前往商品位置",
                                        name, price, stock
                                );
                                speakOut(speechInfo);

                                // 導航到對應的beacon位置
                                String beaconId = PRODUCT_BEACON_MAP.get(name.toLowerCase());
                                if (beaconId != null) {
                                    navigateToBeacon(beaconId);
                                } else {
                                    speakOut("抱歉，找不到該商品的位置信息");
                                }
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                            String parseError = "服務器響應解析失敗";
                            txt.setText(parseError);
                            speakOut(parseError);
                        }
                    });
                } else {
                    runOnUiThread(() -> {
                        String serverError = "服務器錯誤，錯誤碼：" + response.code();
                        txt.setText(serverError);
                        speakOut(serverError);
                    });
                }
            }
        });
    }

//    private void startScanning() {
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
////            scanStatus.setText("Scan Status: Scanning...");
////            detectedDevices.setText("");
//            Toast.makeText(this, "Starting BLE scan...", Toast.LENGTH_SHORT).show();
//            try {
//                bluetoothLeScanner.startScan(scanCallback);
//            } catch (SecurityException e) {
////                Log.e(TAG, "SecurityException: Missing required permissions for BLE scan", e);
//            }
//        } else {
//            Toast.makeText(this, "Missing permissions for BLE scan.", Toast.LENGTH_SHORT).show();
//            checkPermissions(); // Request permissions again if missing
//        }
//    }
//
//    private void stopScanning() {
//        try {
//            bluetoothLeScanner.stopScan(scanCallback);
////            scanStatus.setText("Scan Status: Idle");
//            Toast.makeText(this, "BLE scan stopped.", Toast.LENGTH_SHORT).show();
//        } catch (SecurityException e) {
////            Log.e(TAG, "SecurityException: Missing required permissions to stop BLE scan", e);
//        }
//    }

//    private final ScanCallback scanCallback = new ScanCallback() {
//        @Override
//        public void onScanResult(int callbackType, ScanResult result) {
//            super.onScanResult(callbackType, result);
//
//            BluetoothDevice device = result.getDevice();
//            String deviceAddress = device.getAddress();
//            int rssi = result.getRssi();
//
////            Log.d(TAG, "Detected Device: " + deviceAddress + " RSSI: " + rssi);
//
//            if (deviceAddress.equalsIgnoreCase(beaconId)) {
//                try {
//                    bluetoothLeScanner.stopScan(this);
////                    scanStatus.setText("Scan Status: Target Found");
////                    Log.d(TAG, "Target Beacon Found: " + deviceAddress);
//
//                    // 假設 Tx Power 為 -59 dBm，計算距離
//                    double txPower = -59; // 可根據實際 Beacon 設置調整
//                    double distance = Math.pow(10.0, (txPower - rssi) / 20.0);
//
////                    Log.d(TAG, "Calculated Distance: " + distance + " meters");
//
//                    // 自動移動至目標
//                    if (distance > 1.0) {
//                        robotAPI.motion.moveBody(1.0f, 0, 0); // 向前移動 1 公尺
//                    } else {
////                        Log.d(TAG, "Zenbo is already within 1 meter.");
//                    }
//
//                    // 播報距離資訊
//                    String message = "fuck you.";
//                    textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, null);
//
//                } catch (SecurityException e) {
////                    Log.e(TAG, "SecurityException: Error while stopping scan", e);
//                }
//            }
//        }
//
//        @Override
//        public void onBatchScanResults(@NonNull java.util.List<ScanResult> results) {
//            super.onBatchScanResults(results);
//            for (ScanResult result : results) {
//                onScanResult(0, result); // Use 0 as callbackType
//            }
//        }
//
//        @Override
//        public void onScanFailed(int errorCode) {
//            super.onScanFailed(errorCode);
////            scanStatus.setText("Scan Status: Failed");
////            Log.e(TAG, "Scan failed with error code: " + errorCode);
//        }
//    };

    private void navigateToBeacon(String beaconId) {
        if (robotAPI != null) {
            // 檢查權限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                checkPermissions();
                return;
            }

            // 開始掃描前的語音提示
            speakOut("開始尋找商品位置");

            // 定義移動參數
            final float MOVE_DISTANCE = 0.5f; // 每次移動0.5公尺
            final float ROTATION = 0.0f;      // 不旋轉

            // 建立掃描回調
            ScanCallback beaconScanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    BluetoothDevice device = result.getDevice();
                    String deviceAddress = device.getAddress();
                    int rssi = result.getRssi();

                    if (deviceAddress.equalsIgnoreCase(beaconId)) {
                        // 計算大約距離
                        double txPower = -59; // Beacon的發射功率
                        double distance = Math.pow(10.0, (txPower - rssi) / 20.0);

                        Log.d("Navigation", "Found beacon: " + deviceAddress +
                                ", Distance: " + String.format("%.2f", distance) + "m");

                        // 根據距離決定行動
                        if (distance > 1.5) {
                            // 如果距離大於1米，向前移動
                            robotAPI.motion.moveBody(MOVE_DISTANCE, 0, 0);
                            speakOut("正在接近商品位置");
                        } else {
                            // 如果距離小於1米，停止掃描並到達
                            try {
                                bluetoothLeScanner.stopScan(this);
                                speakOut("已到達商品位置");
                                robotAPI.robot.setExpression(RobotFace.HAPPY); // 顯示開心表情
                            } catch (SecurityException e) {
                                Log.e("Navigation", "停止掃描時發生錯誤", e);
                            }
                        }
                    }
                }

//                @Override
//                public void onScanFailed(int errorCode) {
////                    Log.e("Navigation", "掃描失敗，錯誤碼: " + errorCode);
////                    speakOut("尋找商品位置時發生錯誤");
//                }
            };

            // 開始掃描
            try {
                bluetoothLeScanner.startScan(beaconScanCallback);
            } catch (SecurityException e) {
                Log.e("Navigation", "開始掃描時發生錯誤", e);
                speakOut("無法啟動位置掃描");
            }

            // 設定超時處理（30秒後停止掃描）
            handler.postDelayed(() -> {
                try {
                    bluetoothLeScanner.stopScan(beaconScanCallback);
//                    speakOut("無法找到商品位置，請稍後再試");
                } catch (SecurityException e) {
                    Log.e("Navigation", "停止掃描時發生錯誤", e);
                }
            }, 30000); // 30秒超時

        } else {
            Log.e("Navigation", "RobotAPI 未初始化");
            speakOut("機器人系統尚未準備就緒");
        }
    }




    private void checkDatabaseConnection() {
        String url = "http://192.168.0.105/get_product.php?product_name=test";

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(url)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    speakOut("無法連接到數據庫");
                    txt.setText("數據庫連接失敗");
                });
                Log.e("DB_CONNECTION", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> {
                        speakOut("數據庫連接成功");
                        txt.setText("數據庫連接成功");
                    });
                } else {
                    runOnUiThread(() -> {
                        speakOut("數據庫連接失敗，錯誤碼：" + response.code());
                        txt.setText("數據庫連接失敗，錯誤碼：" + response.code());
                    });
                }
            }
        });
    }

    private void speakOut(String text) {
        if (robotAPI != null) {
            robotAPI.robot.speak(text);
            Log.v("SpeakOut", "Zenbo speaking: " + text);
        } else {
            Log.e("SpeakOut", "RobotAPI is not initialized");
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (robotAPI != null) {
            robotAPI.release();
        }
    }
}
