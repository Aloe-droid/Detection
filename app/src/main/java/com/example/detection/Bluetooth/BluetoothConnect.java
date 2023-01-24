package com.example.detection.Bluetooth;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.Toast;

import com.example.detection.DB.ID;
import com.example.detection.DB.RoomDB;
import com.example.detection.MqttClass;
import com.example.detection.SupportMqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.reactivex.rxjava3.annotations.Nullable;

//블루투스를 연결하는 클래스
public class BluetoothConnect {
    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;  //블루투스 실행을 위한 클래스. 이 객체를 통해 장치 검색, 페어링된 기기 불러오기 등을 할 수 있음.
    private Set<BluetoothDevice> pairedBluetoothDevices; //현재 페어링된 기기 목록을 Set 형태로 저장
    private String address; //블루투스로 연결할 장치의 주소
    private final List<String> bluetoothDeviceList; // 페어링 되지 않은 기기 이름들
    private List<BluetoothDevice> newDevices; // 새로 생긴 기기들
    private BluetoothLeScanner leScanner; //BLE scanner 클래스
    private BluetoothGatt bluetoothGatt; // BLE GATT 클래스
    private BluetoothGattCharacteristic gattCharacteristic; // 특성 클래스
    private final ScanCallback scanCallback;
    final private UUID UUID_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"); //블루투스 범용 고유 식별자
    final int DISCOVERY_TIMEOUT = 3000;

    //생성자
    public BluetoothConnect(Context context) {
        this.context = context;

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        HashSet<BluetoothDevice> devices = new HashSet<>();
        bluetoothDeviceList = new ArrayList<>();

        //스캔 된 결과에 대한 콜백 객체
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) throws SecurityException {
                super.onScanResult(callbackType, result);
                List<ParcelUuid> mServiceUuids = result.getScanRecord().getServiceUuids(); //스캔된 기기들의 UUID 수집
                if (mServiceUuids != null) {
                    for (ParcelUuid uuid : mServiceUuids) {
                        //그중 HM10의 디폴트 UUID 가 보이면 주소를 저장한다.
                        if (uuid.getUuid().equals(UUID_SERVICE)) {
                            devices.add(result.getDevice());
                        }
                    }
                    //set 형태를 다시 arraylist 의 형태로 변경한다.
                    newDevices = new ArrayList<>(devices);
                }
            }
        };
    }

    //블루투스 켜기
    public void bluetoothOn() throws SecurityException {
        //블루투스가 켜져있는지 확인
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            context.startActivity(enableBtIntent);
            bluetoothAdapter.enable();
        }
    }

    //페어링된 기기 list 에 저장
    public void listPairedDevices() throws SecurityException {
        //list 형태로 이름들만 저장
        List<String> listBluetoothDevices = new ArrayList<>();
        //페어링이 가능한 기기 목록을 Set 형태로 저장
        pairedBluetoothDevices = bluetoothAdapter.getBondedDevices();
        if (pairedBluetoothDevices.size() > 0) {
            for (BluetoothDevice device : pairedBluetoothDevices) {
                listBluetoothDevices.add(device.getName());
            }
        }
        listBluetoothDevices.add("새로운 기기 찾기");
        alertDialogBluetooth(listBluetoothDevices, null);
    }


    //리스트 형태의 블루투스 목록을 화면에 보여주기
    public void alertDialogBluetooth(List<String> listBluetoothDevices, @Nullable String msg) {
        //메시지 띄우기
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("장치 선택");
        //동적으로 할당한 list 를 다시 배열로 변환
        CharSequence[] items = listBluetoothDevices.toArray(new CharSequence[0]);
        if (msg != null && msg.equals("new")) {
            builder.setItems(items, (dialog, item) -> connectSelectDevice(items[item].toString(), true));
        } else {
            //기기이름을 클릭하면 해당 기기를 연결하는 메소드로 연결
            builder.setItems(items, (dialog, item) -> connectSelectDevice(items[item].toString(), false));
        }
        //화면에 보여주기
        AlertDialog dialog = builder.create();
        dialog.show();
    }


    //선택된 디바이스 블루투스 연결
    public void connectSelectDevice(String selectedDeviceName, boolean newDevice) throws SecurityException {
        if (selectedDeviceName.equals("새로운 기기 찾기")) {
            //새로운 블루투스 기기 찾기
            searchExtraDevice();
            //로딩창 객체 생성
            ProgressDialog progressDialog = new ProgressDialog(context);
            //로딩창을 투명하게
            progressDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            //로딩창 보여주기
            progressDialog.show();

            // 3 초 뒤에 다시 알람 발송
            new Handler().postDelayed(() -> {
                //로딩창 그만 보여주기 
                progressDialog.cancel();
                //BLE scan Stop
                leScanner.stopScan(scanCallback);

                // 이어서 이름만 따로 ArrayList 로 저장한다. 이후의 Dialog 에 표출될 이름.
                if (newDevices.size() > 0) {
                    for (BluetoothDevice device : newDevices) {
                        bluetoothDeviceList.add(device.getName());
                    }
                }

                alertDialogBluetooth(bluetoothDeviceList, "new");
            }, DISCOVERY_TIMEOUT);

        }
        if (!newDevice) {
            //선택된 디바이스의 이름과 페어링 된 기기목록과 이름이 일치하면 연결
            for (BluetoothDevice device : pairedBluetoothDevices) {
                if (selectedDeviceName.equals(device.getName())) {
                    //BluetoothDevice 의 주소 저장
                    address = device.getAddress();
                    break;
                }
            }
        } else {
            // 새로운페어링 생성
            for (BluetoothDevice device : newDevices) {
                if (device.getName().equals(selectedDeviceName)) {
                    //BluetoothDevice 의 주소 저장
                    address = device.getAddress();
                }
            }
        }
    }

    // 새로운 장치 찾기
    public void searchExtraDevice() throws SecurityException {
        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        leScanner.startScan(scanCallback);
    }

    public void bluetoothConnect() throws SecurityException {
        ID id = RoomDB.getInstance(context).userDAO().getAll().get(0);
        String address = id.getAddress();
        if (address == null) {
            address = this.address;
        }
        //BLE 를 찾아서 연결을 시작한다.
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        bluetoothGatt = device.connectGatt(context, false, bluetoothGattCallback);
    }

    private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) throws SecurityException {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // 새로운 상태가 연결되었을 때, 서비스 검색
                gatt.discoverServices();
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(() -> Toast.makeText(context, "Bluetooth 연결 성공!", Toast.LENGTH_SHORT).show());
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) throws SecurityException {
            super.onServicesDiscovered(gatt, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // BluetoothGatt 의 서비스를 검색 후 호출되는 콜백 메소드
                BluetoothGattService service = gatt.getService(UUID_SERVICE);
                UUID UUID_CHARACTERISTIC = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
                gattCharacteristic = service.getCharacteristic(UUID_CHARACTERISTIC);
                // 해당 특성의 알람을 설정
                gatt.setCharacteristicNotification(gattCharacteristic, true);

                //특성 내부의 descriptor 에서 알람 설정 확인
                BluetoothGattDescriptor descriptor = gattCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            // 캐릭터리스틱이 변경될 때 호출되는 콜백 메소드
            try {
                byte[] bytes = characteristic.getValue();
                String msg = new String(bytes, StandardCharsets.UTF_8);
                String[] degreeAndAck = msg.split("/");
                String degree = degreeAndAck[0];
                String ACK = degreeAndAck[1];
                //만약 아두이노로부터 각도제어가 끝난후 ack 가 오면 mqtt 로 제어가 끝났다고 서버에게 알림.
                if (ACK.equals("ack")) {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("CameraId", Integer.parseInt(RoomDB.getInstance(context).userDAO().getAll().get(0).getCameraId()));
                    jsonObject.put("Degree", Integer.parseInt(degree));
                    MqttClient mqttClient = SupportMqtt.getInstance().getMqttClient();
                    mqttClient.publish(MqttClass.TOPIC_MOTOR_ACK, new MqttMessage(jsonObject.toString().getBytes(StandardCharsets.UTF_8)));
                }
            } catch (JSONException | MqttException e) {
                e.printStackTrace();
            }
        }
    };

    //종료하는 메서드
    public void close() throws SecurityException {
        bluetoothGatt.close();
    }

    //메시지 전송을 위한 메서드
    public void write(String message) throws SecurityException {
        if (gattCharacteristic != null) {
            //해당 특성에 보낼 값을 저장한다.
            gattCharacteristic.setValue((message + ".").getBytes(StandardCharsets.UTF_8));
            //특성에 전송한다.
            bluetoothGatt.writeCharacteristic(gattCharacteristic);
        }
    }

    //블루투스 GATT 서버가 살아있는지 확인하는 메서드
    public boolean checkGATT() {
        return bluetoothGatt != null;
    }


    public String getAddress() {
        return address;
    }
}
