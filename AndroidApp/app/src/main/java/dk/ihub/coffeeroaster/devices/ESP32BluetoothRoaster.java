package dk.ihub.coffeeroaster.devices;

import dk.ihub.coffeeroaster.MyApp;
import dk.ihub.coffeeroaster.events.CoffeeRoasterEvent;
import dk.ihub.coffeeroaster.events.ConnectionEvent;
import dk.ihub.coffeeroaster.events.ICoffeeRoasterConnectionListener;
import dk.ihub.coffeeroaster.events.ICoffeeRoasterEventListener;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class ESP32BluetoothRoaster implements ICoffeeRoaster {

    public static final String DEFAULT_ESP32_BT_ADDRESS = "3C:61:05:16:C8:36";
    public static final String TAG = ESP32BluetoothRoaster.class.getName();

    private int connectionState;

    private final BluetoothManager btManager;
    private final BluetoothAdapter btAdapter;
    private BluetoothGatt btGatt;
    private BluetoothGattService coffeeRoasterService;
    private static final UUID COFFEE_ROASTER_SERVICE_UUID = UUID.fromString("91BAD492-B950-4226-AA2B-4EDE9FA42F59");
    private BluetoothGattCharacteristic beanTemperatureCharacteristic;
    private static final UUID BEAN_TEMPERATURE_CHARACTERISTIC_UUID = UUID.fromString("CBA1D466-344C-4BE3-AB3F-189F80DD7518");
    private BluetoothGattCharacteristic dutyCycleCharacteristic;
    private static final UUID DUTY_CYCLE_CHARACTERISTIC_UUID = UUID.fromString("CA73B3BA-39F6-4AB3-91AE-186DC9577D99");
    private final String esp32BtDeviceAddress;


    private float dutyCycle;
    private float temperatureC;

    private List<ICoffeeRoasterEventListener> eventListeners;
    private List<ICoffeeRoasterConnectionListener> connectionListeners;

    @SuppressLint("MissingPermission")
    public ESP32BluetoothRoaster(String esp32BtDeviceAddress) {
        if (esp32BtDeviceAddress == null)
            throw new IllegalArgumentException("esp32BtDeviceAddress must not be null.");

        this.esp32BtDeviceAddress = esp32BtDeviceAddress;
        this.btManager = MyApp.getAppContext().getSystemService(BluetoothManager.class);
        this.btAdapter = btManager.getAdapter();
        if (btAdapter == null)
            throw new RuntimeException("Could not access Bluetooth adapter. Check permissions.");
    }

    @Override
    @SuppressLint("MissingPermission")
    public boolean connect() {
        final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // successfully connected to the GATT Server
                    connectionState = BluetoothProfile.STATE_CONNECTED;
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // disconnected from the GATT Server
                    connectionState = BluetoothProfile.STATE_DISCONNECTED;
                }
                notifyListeners(new ConnectionEvent(newState));
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    for (BluetoothGattService service : gatt.getServices()) {
                        Log.d(TAG, "Service: " + service.getUuid().toString());
                        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                            Log.d(TAG, "\tCharacteristic: " + characteristic.getUuid());
                        }
                    }

                    coffeeRoasterService = btGatt.getService(COFFEE_ROASTER_SERVICE_UUID);
                    if (coffeeRoasterService == null) {
                        String errMsg = String.format("Coffee roaster BLE Service not found. UUID: %s", COFFEE_ROASTER_SERVICE_UUID);
                        throw new RuntimeException(errMsg);
                    }

                    beanTemperatureCharacteristic = coffeeRoasterService.getCharacteristic(BEAN_TEMPERATURE_CHARACTERISTIC_UUID);
                    if (beanTemperatureCharacteristic != null) {
                        UUID descriptorUUID = beanTemperatureCharacteristic.getDescriptors().get(0).getUuid();
                        if (setCharacteristicNotification(btGatt, beanTemperatureCharacteristic, descriptorUUID, true)) {
                            Log.d(TAG, "Bean temperature notification activated.");
                        }
                    } else {
                        Log.e(TAG, "Bean temperature characteristic was not found.");
                    }
                } else {
                    Log.e(TAG, "onServicesDiscovered received: " + status);
                }

            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                Log.d(TAG, "Characteristic changed: " + characteristic.getUuid());
                if (BEAN_TEMPERATURE_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                    temperatureC = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                    dutyCycle = getDutyCycle();

                    notifyListeners(new CoffeeRoasterEvent(temperatureC, dutyCycle));
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (DUTY_CYCLE_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                    Log.d(TAG, "onCharacteristicRead: Duty cycle.");
                    try {
                        dutyCycle = Float.parseFloat(characteristic.getStringValue(0));
                    } catch (NumberFormatException ex) {
                        Log.w(TAG, "onCharacteristicRead: Cannot parse empty string to Duty Cycle value");
                    }
                }
            }
        };

        try {
            final BluetoothDevice btDevice = btAdapter.getRemoteDevice(esp32BtDeviceAddress);
            if (btDevice != null) {
                btGatt = btDevice.connectGatt(MyApp.getAppContext(), true, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
                // Discover services must be called on the UI threat,
                // and a short delay must be used after connecting to the gatt.
                // Otherwise, the callback onServicesDiscovered() is never called.
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    boolean result = btGatt.discoverServices();
                    Log.d(TAG, "Discover Services started: " + result);
                }, 1000);

                return true;
            }
        } catch (IllegalArgumentException ex) {
            String errorMsg = String.format("Device not found with provided address '%s'.  Unable to connect.", esp32BtDeviceAddress);
            Log.e(TAG, errorMsg);
            return false;
        } catch (RuntimeException ex) {
            Log.e(TAG, ex.getMessage());
            return false;
        }

        return false;
    }

    /**
     * Enables notification for characteristic over BLE.
     * See: https://stackoverflow.com/questions/32184536/enabling-bluetooth-characteristic-notification-in-android-bluetooth-low-energy
     * @param bluetoothGatt
     * @param characteristic
     * @param enable
     * @return
     */
    @SuppressLint("MissingPermission")
    private boolean setCharacteristicNotification(BluetoothGatt bluetoothGatt,
                                                  BluetoothGattCharacteristic characteristic,
                                                  UUID descriptorUUID,
                                                  boolean enable) {
        Log.d(TAG, String.format("setCharacteristicNotification(%s)", enable));
        bluetoothGatt.setCharacteristicNotification(characteristic, enable);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(descriptorUUID);
        descriptor.setValue(enable ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : new byte[]{0x00, 0x00});
        return bluetoothGatt.writeDescriptor(descriptor); //descriptor write operation successfully started?
    }

    @Override
    @SuppressLint("MissingPermission")
    public void disconnect() {
        if (btGatt == null) return;
        btGatt.disconnect();
        btGatt.close();
    }

    @Override
    @SuppressLint("MissingPermission")
    public float getBeanTemperatureCelsius() {
        if (btGatt == null) return temperatureC;

        BluetoothGattCharacteristic characteristic = coffeeRoasterService.getCharacteristic(BEAN_TEMPERATURE_CHARACTERISTIC_UUID);
        if (btGatt.readCharacteristic(characteristic)) {
            Log.d(TAG, "getBeanTemperatureCelsius: Read value");
            return characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
        }

        Log.d(TAG, "getBeanTemperatureCelsius: Returned latest value.");
        return temperatureC; // return latest received value if characteristic read fails.
    }

    @Override
    @SuppressLint("MissingPermission")
    public float getDutyCycle() {
        if (btGatt == null) return dutyCycle;

        BluetoothGattCharacteristic characteristic = coffeeRoasterService.getCharacteristic(DUTY_CYCLE_CHARACTERISTIC_UUID);
        if (btGatt.readCharacteristic(characteristic)) {
            Log.d(TAG, "getDutyCycle: Read value");
            return dutyCycle;
        }

        Log.d(TAG, "getDutyCycle: Returned latest value");
        return dutyCycle; // return latest received value if characteristic read fails.
    }

    @Override
    @SuppressLint("MissingPermission")
    public void subscribe(ICoffeeRoasterEventListener listener) {
        if (eventListeners == null) {
            eventListeners = new ArrayList<>();
        }

        eventListeners.add(listener);
    }

    public void addConnectionListener(ICoffeeRoasterConnectionListener listener) {
        if (connectionListeners == null) {
            connectionListeners = new ArrayList<>();
        }

        connectionListeners.add(listener);
    }

    @SuppressLint("MissingPermission")
    @Override
    public boolean setDutyCycle(float value) {
        if (btGatt == null) return false;
        if (! (0 <= value && value <= 100)) { // Minimum and maximum duty cycle allowed is [0, 100].
            return false;
        }

        BluetoothGattCharacteristic characteristic = coffeeRoasterService.getCharacteristic(DUTY_CYCLE_CHARACTERISTIC_UUID);
        characteristic.setValue(String.valueOf((int)value));
        if (btGatt.writeCharacteristic(characteristic)) {
            dutyCycle = (int)value;
            Log.d(TAG, "setDutyCycle(float value): Updated duty cycle to " + dutyCycle);
            return true;
        }

        return false;
    }

    private void notifyListeners(CoffeeRoasterEvent event) {
        if (this.eventListeners == null) return;

        try {
            for (ICoffeeRoasterEventListener l : this.eventListeners) {
                l.onRoasterEvent(event);
            }
        } catch (Error e) {
            Log.e(TAG, e.getMessage());
        }
    }

    private void notifyListeners(ConnectionEvent event) {
        if (this.connectionListeners == null) return;

        try {
            for (ICoffeeRoasterConnectionListener l : this.connectionListeners) {
                l.onConnectionStateChanged(event);
            }
        } catch (Error e) {
            Log.e(TAG, e.getMessage());
        }
    }
}
