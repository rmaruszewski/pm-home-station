/*
 * pm-home-station
 * 2017 (C) Copyright - https://github.com/rjaros87/pm-home-station
 * License: GPL 3.0
 */

package pmstation;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.crashlytics.android.Crashlytics;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import io.fabric.sdk.android.Fabric;
import pmstation.core.plantower.IPlanTowerObserver;
import pmstation.core.plantower.ParticulateMatterSample;
import pmstation.plantower.BluetoothLeService;
import pmstation.plantower.PlanTowerService;
import pmstation.plantower.USBService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MainActivity extends AppCompatActivity implements IPlanTowerObserver {
    public static final String VALUES_FRAGMENT = "VALUES_FRAGMENT";
    public static final String CHART_FRAGMENT = "CHART_FRAGMENT";
    public static final String MAP_FRAGMENT = "MAP_FRAGMENT";
    public static final String SETTINGS_FRAGMENT = "SETTINGS_FRAGMENT";
    public static final String ABOUT_FRAGMENT = "ABOUT_FRAGMENT";
    public static final String LAST_SINGLE_PANE_FRAGMENT = "lastSinglePaneFragment";
    private static final String TAG = MainActivity.class.getSimpleName();

    private final List<IPlanTowerObserver> valueObservers = Collections.synchronizedList(new ArrayList<>());
    private CopyOnWriteArrayList<ParticulateMatterSample> values = new CopyOnWriteArrayList<>();
    private Menu menu;
    private ImageView smog;
    private String lastSinglePaneFragment;
    private USBService usbService;
    private boolean running = false;
    private boolean justConnected;
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            
            switch (action) {
                case USBService.ACTION_USB_PERMISSION_GRANTED: // USB PERMISSION GRANTED
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    // if was just connected then it's not asleep
                    if (!justConnected) {
                        usbService.wakeUp();
                    }
                    setStatus(true);
                    break;
                case USBService.ACTION_USB_PERMISSION_NOT_GRANTED: // USB PERMISSION NOT GRANTED
                    setStatus(false);
                    break;
                case USBService.ACTION_USB_CONNECTED: // NO USB CONNECTED
                    justConnected = true;
                    break;
                case USBService.ACTION_USB_DISCONNECTED: // USB DISCONNECTED
                    setStatus(false);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    break;
            }
        }
    };
    private DataHandler dataHandler;
    private final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            usbService = ((USBService.LocalBinder) service).getService();
            usbService.setHandler(dataHandler);

            if (canAccessLocation()) {
                usbService.startLocationUpdates();
            }

            if (isEmulator()) {
                usbService.startFakeDataThread();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            usbService.stopFakeDataThread();
            usbService.stopLocationUpdates();
            usbService = null;
        }
    };

    private BluetoothLeService bluetoothLeService;
    private String deviceAddress;
    private final ServiceConnection btConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            bluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            bluetoothLeService.setHandler(dataHandler);
            if (!bluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                return;
            }
            // Automatically connects to the device upon successful start-up initialization.
            bluetoothLeService.connect(deviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            bluetoothLeService = null;
        }
    };
    private boolean btConnected = false;
    private final BroadcastReceiver gattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                btConnected = true;
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                btConnected = false;
            }
        }
    };

    private FusedLocationProviderClient fusedLocationClient;

    public static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT);
    }

    public static void tintMenuItem(MenuItem item) {
        Drawable icon = item.getIcon();
        icon.mutate();
        icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        return intentFilter;
    }


    private ValuesFragment getDetatchedValuesFragment(boolean popBackStack) {
        FragmentManager fm = getSupportFragmentManager();
        ValuesFragment valuesFragment = (ValuesFragment) getSupportFragmentManager().findFragmentByTag(VALUES_FRAGMENT);
        if (valuesFragment == null) {
            valuesFragment = new ValuesFragment();
        } else {
            if (popBackStack) {
                fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            }
            fm.beginTransaction().remove(valuesFragment).commit();
            fm.executePendingTransactions();
        }
        return valuesFragment;
    }

    private ChartFragment getDetatchedChartFragment() {
        FragmentManager fm = getSupportFragmentManager();
        ChartFragment chartFragment = (ChartFragment) getSupportFragmentManager().findFragmentByTag(CHART_FRAGMENT);
        if (chartFragment == null) {
            chartFragment = new ChartFragment();
        } else {
            fm.beginTransaction().remove(chartFragment).commit();
            fm.executePendingTransactions();
        }
        return chartFragment;
    }

    private MapFragment getDetachedMapFragment() {
        FragmentManager fm = getSupportFragmentManager();
        MapFragment mapFragment = (MapFragment) getSupportFragmentManager().findFragmentByTag(MAP_FRAGMENT);
        if (mapFragment == null) {
            mapFragment = new MapFragment();
        } else {
            fm.beginTransaction().remove(mapFragment).commit();
            fm.executePendingTransactions();
        }
        return mapFragment;
    }

    private void openSinglePaneChartFragment() {
        FragmentManager fm = getSupportFragmentManager();
        fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        ChartFragment detailFragment = getDetatchedChartFragment();
        FragmentTransaction fragmentTransaction = fm.beginTransaction();
        fragmentTransaction.replace(R.id.single_pane, detailFragment, CHART_FRAGMENT);
        fragmentTransaction.addToBackStack(null);
        fragmentTransaction.commit();
    }

    private void openMapFragment() {
        FragmentManager fm = getSupportFragmentManager();
        fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        MapFragment detailFragment = getDetachedMapFragment();
        FragmentTransaction fragmentTransaction = fm.beginTransaction();
        fragmentTransaction.replace(R.id.single_pane, detailFragment, MAP_FRAGMENT);
        fragmentTransaction.addToBackStack(null);
        fragmentTransaction.commit();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BuildConfig.DEBUG) {
            Fabric.with(this, new Crashlytics());
        }
        dataHandler = new DataHandler(this);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        setContentView(R.layout.activity_main);

        smog = findViewById(R.id.smog);
        smog.setAlpha(0f);

        addValueObserver(this);

        boolean dualPane = findViewById(R.id.dual_pane) != null;

        if (savedInstanceState != null) {
            lastSinglePaneFragment = savedInstanceState.getString(LAST_SINGLE_PANE_FRAGMENT);
            running = savedInstanceState.getBoolean("running");
            if (running) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            CopyOnWriteArrayList<ParticulateMatterSample> savedValues = (CopyOnWriteArrayList<ParticulateMatterSample>) savedInstanceState.getSerializable("values");

            if (savedValues != null) {
                values.addAll(savedValues);
            }
        }

        FragmentManager fm = getSupportFragmentManager();

        if (!dualPane && fm.findFragmentById(R.id.single_pane) == null) {
            ValuesFragment valuesFragment = getDetatchedValuesFragment(false);
            fm.beginTransaction().add(R.id.single_pane, valuesFragment, VALUES_FRAGMENT).commit();
            if (CHART_FRAGMENT.equals(lastSinglePaneFragment)) {
                openSinglePaneChartFragment();
            }
        }
        if (dualPane && fm.findFragmentById(R.id.values_dual) == null) {
            ValuesFragment valuesFragment = getDetatchedValuesFragment(true);
            fm.beginTransaction().add(R.id.values_dual, valuesFragment, VALUES_FRAGMENT).commit();
        }
        if (dualPane && fm.findFragmentById(R.id.chart_dual) == null) {
            ChartFragment chartFragment = getDetatchedChartFragment();
            fm.beginTransaction().add(R.id.chart_dual, chartFragment, CHART_FRAGMENT).commit();
        }

//        handlerThread = new HandlerThread("bluetoothHandler");
//        handlerThread.start();

        deviceAddress = PreferenceManager.getDefaultSharedPreferences(this).getString("bt_mac", "00:25:83:00:62:E7");
        startService(BluetoothLeService.class, btConnection);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        if (!canAccessLocation()) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 1337);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean canAccessLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case 1337:
                if (canAccessLocation() && usbService != null) {
                    usbService.startLocationUpdates();
                }
                break;
        }
    }

    @Override
    protected void onStop() {
        if (!isChangingConfigurations() && usbService != null) {
            usbService.sleep();
        }
        unbindService(usbConnection);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Registering receiver");
        registerReceiver();
        startService(USBService.class, usbConnection); // Start UsbService(if it was not started before) and Bind it
        if (usbService != null) {
            usbService.wakeUp();
        }

        registerReceiver(gattUpdateReceiver, makeGattUpdateIntentFilter());
        if (bluetoothLeService != null) {
            final boolean result = bluetoothLeService.connect(deviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "Unregistering receiver");
        unregisterReceiver(usbReceiver);
        unregisterReceiver(gattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(btConnection);
        bluetoothLeService = null;
    }


    public List<ParticulateMatterSample> getValues() {
        return values;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_status, menu);
        this.menu = menu;
        setStatus(isRunning());

        int[] arr = {R.id.action_chart, R.id.action_map, R.id.action_connected, R.id.action_disconnected};
        for (int i : arr) {
            MenuItem item = menu.findItem(i);
            if (item != null) {
                MainActivity.tintMenuItem(item);
            }
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_chart:
                showChart();
                return true;
            case R.id.action_map:
                showMap();
                return true;
            case R.id.action_connected:
                Log.d(TAG, "Trying to disconnect");
                if (usbService.sleep()) {
                    setStatus(false);
                }
                return true;
            case R.id.action_disconnected:
                Log.d(TAG, "Trying to connect");
                if (usbService.wakeUp()) {
                    setStatus(true);
                }
                return true;
            case R.id.action_settings:
                showSingleFragment(SETTINGS_FRAGMENT);
                return true;
            case R.id.action_about:
                showSingleFragment(ABOUT_FRAGMENT);
                return true;
            case R.id.action_load_values: {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, 110);
                return true;
            }
            case R.id.action_save_values: {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, "values.ser");
                startActivityForResult(intent, 111);
                return true;
            }
            case R.id.action_reset_values:
                values.clear();
                Toast.makeText(getApplicationContext(), "Values have been reset", Toast.LENGTH_SHORT).show();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == 111) {
                try (OutputStream output = getApplicationContext().getContentResolver().openOutputStream(data.getData());
                    ObjectOutputStream out = new ObjectOutputStream(output)) {
                    out.writeObject(values);
                    out.flush();
                } catch (Exception e) {
                    Toast.makeText(getApplicationContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Error while saving file", e);
                }
            } else if (requestCode == 110) {
                try (InputStream input = getApplicationContext().getContentResolver().openInputStream(data.getData());
                     ObjectInputStream in = new ObjectInputStream(input)) {
                    CopyOnWriteArrayList<ParticulateMatterSample> o = (CopyOnWriteArrayList<ParticulateMatterSample>) in.readObject();
                    values.clear();
                    values.addAll(o);
                } catch (Exception e) {
                    Toast.makeText(getApplicationContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Error while saving file", e);
                }
            }

        }
    }

    private void showChart() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(CHART_FRAGMENT);
        if (fragment != null && !fragment.isDetached() && fragment.getId() != R.id.chart_dual) {
            return;
        }
        openSinglePaneChartFragment();
    }

    private void showMap() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(MAP_FRAGMENT);
        if (fragment != null && !fragment.isDetached()) {
            return;
        }
        openMapFragment();
    }

    private void showSingleFragment(String fragmentTag) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(fragmentTag);
        if (fragment == null) {
            switch (fragmentTag) {
                case SETTINGS_FRAGMENT:
                    fragment = new SettingsFragment();
                    break;
                case ABOUT_FRAGMENT:
                    fragment = new AboutFragment();
                    break;
            }
        } else {
            return;
        }
        getSupportFragmentManager().beginTransaction()
                                   .replace(android.R.id.content, fragment, fragmentTag).addToBackStack(null)
                                   .commit();
    }

    void setStatus(boolean connected) {
        this.running = connected;
        if (menu == null) {
            return;
        }
        menu.getItem(0).setVisible(connected);
        menu.getItem(1).setVisible(!connected);
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(USBService.ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(USBService.ACTION_USB_DISCONNECTED);
        filter.addAction(USBService.ACTION_USB_PERMISSION_NOT_GRANTED);
        registerReceiver(usbReceiver, filter);
    }

    @Override
    public void update(ParticulateMatterSample sample) {
        values.add(sample);
        AQIColor pm25Color = AQIColor.fromPM25Level(sample.getPm2_5());
        runOnUiThread(() -> smog.animate().alpha(pm25Color.getAlpha()));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("running", running);
        outState.putSerializable("values", values);
        Fragment singleFragment = getSupportFragmentManager().findFragmentById(R.id.single_pane);
        if (singleFragment == null) {
            outState.putString(LAST_SINGLE_PANE_FRAGMENT, lastSinglePaneFragment);
        } else {
            outState.putString(LAST_SINGLE_PANE_FRAGMENT,
                               singleFragment instanceof ValuesFragment ? VALUES_FRAGMENT : CHART_FRAGMENT);
        }

        super.onSaveInstanceState(outState);
    }

    private void startService(Class<?> service, ServiceConnection serviceConnection) {
        Intent bindingIntent = new Intent(this, service);
        bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    public void addValueObserver(IPlanTowerObserver observer) {
        valueObservers.add(observer);
    }

    public void removeValueObserver(IPlanTowerObserver observer) {
        valueObservers.remove(observer);
    }

    private void notifyAllObservers(final ParticulateMatterSample sample) {
        synchronized (valueObservers) {
            for (IPlanTowerObserver valueObserver : valueObservers) {
                valueObserver.update(sample);
            }
        }
    }

    public void wakeConnection() {
        if (usbService == null) {
            return;
        }
        usbService.wakeWorkerThread();
    }

    private static class DataHandler extends Handler {
        private final WeakReference<MainActivity> activity;

        DataHandler(MainActivity activity) {
            this.activity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PlanTowerService.DATA_AVAILABLE:
                    ParticulateMatterSample sample = (ParticulateMatterSample) msg.obj;
                    MainActivity mainActivity = activity.get();
                    if (mainActivity != null) {
                        mainActivity.notifyAllObservers(sample);
                    }
                    break;
            }
        }
    }
}
