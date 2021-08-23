/*  Copyright (C) 2016-2020 Andreas Shimokawa, Carsten Pfeiffer, Daniele
    Gobbetti, Johannes Tysiak, Taavi Eomäe, vanous

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.aware.Characteristics;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import de.cketti.library.changelog.ChangeLog;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.adapter.GBDeviceAdapterv2;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.pebble.GBDeviceEventDataLogging;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEOperation;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.HuamiSupport;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.Logging;

import static nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic.UUID_CHARACTERISTIC_ALERT_LEVEL;

//TODO: extend AbstractGBActivity, but it requires actionbar that is not available
public class ControlCenterv2 extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, GBActivity {

    public static final int MENU_REFRESH_CODE = 1;
    private static PhoneStateListener fakeStateListener;
    private static final Logger LOG = LoggerFactory.getLogger(DebugActivity.class);

    //needed for KK compatibility
    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    private DeviceManager deviceManager;
    private GBDeviceAdapterv2 mGBDeviceAdapter;
    private RecyclerView deviceListView;
    private FloatingActionButton fab;
    private boolean isLanguageInvalid = false;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (Objects.requireNonNull(action)) {
                case GBApplication.ACTION_LANGUAGE_CHANGE:
                    setLanguage(GBApplication.getLanguage(), true);
                    break;
                case GBApplication.ACTION_QUIT:
                    finish();
                    break;
                case DeviceManager.ACTION_DEVICES_CHANGED:
                    refreshPairedDevices();
                    break;
            }
        }
    };
    private boolean pesterWithPermissions = true;


    //added
    Handler mHandler;
    private final String DEFAULT = "DEFAULT";

    public static int count = 0;
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AbstractGBActivity.init(this, AbstractGBActivity.NO_ACTIONBAR);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_controlcenterv2);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.controlcenter_navigation_drawer_open, R.string.controlcenter_navigation_drawer_close);
        drawer.setDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        //end of material design boilerplate
        deviceManager = ((GBApplication) getApplication()).getDeviceManager();

        deviceListView = findViewById(R.id.deviceListView);
        deviceListView.setHasFixedSize(true);
        deviceListView.setLayoutManager(new LinearLayoutManager(this));

        List<GBDevice> deviceList = deviceManager.getDevices();
        mGBDeviceAdapter = new GBDeviceAdapterv2(this, deviceList);

        deviceListView.setAdapter(this.mGBDeviceAdapter);

        fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchDiscoveryActivity();
            }
        });

        showFabIfNeccessary();

        /* uncomment to enable fixed-swipe to reveal more actions

        ItemTouchHelper swipeToDismissTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.LEFT , ItemTouchHelper.RIGHT) {
            @Override
            public void onChildDraw(Canvas c, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                if(dX>50)
                    dX = 50;
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);

            }

            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                GB.toast(getBaseContext(), "onMove", Toast.LENGTH_LONG, GB.ERROR);

                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                GB.toast(getBaseContext(), "onSwiped", Toast.LENGTH_LONG, GB.ERROR);

            }

            @Override
            public void onChildDrawOver(Canvas c, RecyclerView recyclerView,
                                        RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                        int actionState, boolean isCurrentlyActive) {
            }
        });

        swipeToDismissTouchHelper.attachToRecyclerView(deviceListView);
        */

        registerForContextMenu(deviceListView);

        IntentFilter filterLocal = new IntentFilter();
        filterLocal.addAction(GBApplication.ACTION_LANGUAGE_CHANGE);
        filterLocal.addAction(GBApplication.ACTION_QUIT);
        filterLocal.addAction(DeviceManager.ACTION_DEVICES_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filterLocal);

        refreshPairedDevices();

        /*
         * Ask for permission to intercept notifications on first run.
         */
        Prefs prefs = GBApplication.getPrefs();
        pesterWithPermissions = prefs.getBoolean("permission_pestering", true);

        Set<String> set = NotificationManagerCompat.getEnabledListenerPackages(this);
        if (pesterWithPermissions) {
            if (!set.contains(this.getPackageName())) { // If notification listener access hasn't been granted
                Intent enableIntent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                startActivity(enableIntent);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkAndRequestPermissions();
        }

        ChangeLog cl = createChangeLog();
        if (cl.isFirstRun()) {
            try {
                cl.getLogDialog().show();
            } catch (Exception ignored) {
                GB.toast(getBaseContext(), "Error showing Changelog", Toast.LENGTH_LONG, GB.ERROR);

            }
        }

        GBApplication.deviceService().start();

        if (GB.isBluetoothEnabled() && deviceList.isEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            startActivity(new Intent(this, DiscoveryActivity.class));
        } else {
            GBApplication.deviceService().requestDeviceInfo();
        }

        /**-------------------------------------------**/
        /**
         * added for lab research
         * Algorithm    : when heart rate is measured, it is considered user is wearing the device
         *              TODO: 1. Send notification to user when there is no heart rate measurement for specific time
         *                    2. Make notification when there is no movement for specific time (realtime heart rate is measured)
         *                    3. When notified for exercise and measured HuamiSupport.STEP >= 10, destroy notification
         *                    4. When user is moving (that is HuamiSupport.STEP > 0), reset the timer
         */
        int MUTABILITY=0;
        int ONESECOND=1;
        int FIVESECOND=2;
        final boolean[] flag = {false};
//        mHandler = new Handler();
//        createNotificationChannel(DEFAULT, "default channel", NotificationManager.IMPORTANCE_HIGH);
//
//        Intent intent = new Intent(this, ControlCenterv2.class);
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
//
//                new Thread(new Runnable() {
//
//                    int total_step = 0;
//                    @Override
//                    public void run() {
//                                    while (true) {
//                                        switch (MUTABILITY) {
//                                            case 0:
                                                LOG.debug("test heart: " + HuamiSupport.HEART_RATE);
                                                LOG.debug("test step: " + HuamiSupport.STEP);

//                                                if (HuamiSupport.HEART_RATE > 0 && HuamiSupport.STEP <= 10) {  // 심장 박동 감지 됨
//                                                    stepTimer++;
//                                                    LOG.debug("check Activity "+stepTimer+" : "+HuamiSupport.HEART_RATE+"  "+HuamiSupport.TOTAL_STEP +" "+(HuamiSupport.TOTAL_STEP - beforeStep));
//                                                    if (stepTimer == -1) {
//                                                        beforeStep = HuamiSupport.TOTAL_STEP;
//                                                    }
//                                                    if (stepTimer == 0) {
//
//                                                    }else if (stepTimer < 40 || HuamiSupport.TOTAL_STEP-beforeStep>10) {                 //알람 종료
//                                                        if (HuamiSupport.TOTAL_STEP-beforeStep > 10) {
//                                                            start=1;
//                                                            destroyNotification(1956);
//                                                        }
//                                                    }
//                                                    else if(stepTimer==40){
//                                                            destroyNotification(1956);
//                                                        beforeStep = HuamiSupport.TOTAL_STEP;
//                                                    }
//                                                    else if (stepTimer == resetTime) {                 //주기
//                                                        start = 0;
//
//                                                        if (beforeStep != 0 && HuamiSupport.TOTAL_STEP - beforeStep < 10) {        //이전과 비교해서 걸음수 체크
//                                                                mHandler.post(new Runnable() {
//                                                                @Override
//                                                                public void run() {
//                                                                    if(HuamiSupport.SEND_DATA==0&&count==0) {
////                                                                        createNotification(DEFAULT, 1956, "운동하세요", "어깨 돌리기 10회 이상 실시!", intent);
//
//                                                                        count=1;
//                                                                    }
//                                                                }
//                                                            });
//                                                            vibration_timer(1,1,MUTABILITY);


//                                                        }
//
//                                                        beforeStep = HuamiSupport.TOTAL_STEP;
//                                                        stepTimer = -1;
//                                                    }



//                                                    if (count < 1) {
//                                                        mHandler.post(new Runnable() {
//                                                            @Override
//                                                            public void run() {
//                                                                createNotification(DEFAULT, 1956, "운동하세요", "어깨 돌리기 10회 이상 실시!", intent);
//
//                                                            }
//                                                        });
//                                                    }
//                                                }

//                                                break;
//
//                                            case 1:
//                                                checkActivity(1,1,ONESECOND);
//                                                break;
//                                            case 2:
//                                                checkActivity(1,1,FIVESECOND);
//                                                break;
//                                        }

//                                        try {
//                                            Thread.sleep(1000);
//                                        } catch (InterruptedException e) {
//                                            e.printStackTrace();
//                                        }
//
//                        }
//                    }
//                }).start();


//        final Timer timer = new Timer();
//        TimerTask Task = new TimerTask() {
//            @Override
//            public void run() {
//
//
//                switch (MUTABILITY) {
//                    case 0:
//                        LOG.debug("test heart: " + HuamiSupport.HEART_RATE);
//                        LOG.debug("test step: " + HuamiSupport.STEP);
//
//                        if (HuamiSupport.HEART_RATE > 0) {  // 심장 박동 감지 됨
//                            stepTimer++;
//                            LOG.debug("check Activity "+stepTimer+" : "+HuamiSupport.HEART_RATE+"  "+HuamiSupport.TOTAL_STEP +" "+(HuamiSupport.TOTAL_STEP - beforeStep));
//                            if (stepTimer == -1) {
//                                beforeStep = HuamiSupport.TOTAL_STEP;
//                            }
//                            if (stepTimer == 0) {
//
//                            }else if (stepTimer < 40 || HuamiSupport.STEP>10) {                 //알람 종료
//                                if (HuamiSupport.TOTAL_STEP-beforeStep > 10) {
//                                    start=1;
//                                    destroyNotification(1956);
//                                }
////                                start=intent.getIntExtra("start",0);
//                                LOG.debug("check Activity "+start);
//                            }
//                            else if(stepTimer==40){
//                                destroyNotification(1956);
//                                start=1;
//                                beforeStep = HuamiSupport.TOTAL_STEP;
//                            }
//                            else if (stepTimer == resetTime) {                 //주기
//                                start = 0;
//
//                                if (beforeStep != 0 && HuamiSupport.TOTAL_STEP - beforeStep < 10) {        //이전과 비교해서 걸음수 체크
//
//                                    mHandler.post(new Runnable() {
//                                        @Override
//                                        public void run() {
////                                            createNotification(DEFAULT, 1956, "운동하세요", "어깨 돌리기 10회 이상 실시!", intent);
//
//                                        }
//                                    });
//                                    vibration_timer(1,1,MUTABILITY);
//                                }
//
//                                beforeStep = HuamiSupport.TOTAL_STEP;
//                                stepTimer = -1;
//                            }
//
//
//
////                                                    if (count < 1) {
////                                                        mHandler.post(new Runnable() {
////                                                            @Override
////                                                            public void run() {
////                                                                createNotification(DEFAULT, 1956, "운동하세요", "어깨 돌리기 10회 이상 실시!", intent);
////
////                                                            }
////                                                        });
////                                                    }
//                        }
//
//                        break;
//
//                    case 1:
//                        checkActivity(1,1,ONESECOND);
//                        break;
//                    case 2:
//                        checkActivity(1,1,FIVESECOND);
//                        break;
//                }
//            }
//        };
//        timer.schedule(Task, 0, 1000);


        }
    /**Add Alram Algorithm**/
//    private void vibrateOnce() {
//
//        BluetoothGattCharacteristic characteristic = getCharacteristic(UUID_CHARACTERISTIC_ALERT_LEVEL);
//        try {
//            TransactionBuilder builder = performInitialized("Vibrate once");
//
//            builder.write(characteristic, new byte[]{3});
//            builder.write(characteristic, new byte[]{3});
//            builder.queue(getQueue());
//        } catch (IOException e) {
//            LOG.error("error while sending simple vibrate command", e);
//        }
//    }



    public void notification(){
        NotificationSpec notificationSpec = new NotificationSpec();
        String testString="운동하세요";
        notificationSpec.phoneNumber = testString;
        notificationSpec.body = testString;
        notificationSpec.sender = testString;
        notificationSpec.subject = testString;
        notificationSpec.type = NotificationType.UNKNOWN;
        notificationSpec.pebbleColor = notificationSpec.type.color;
        GBApplication.deviceService().onNotification(notificationSpec);
    }
    private void vibration_timer(int period, final int time,int casenum) {
        final Timer timer = new Timer();
        TimerTask Task = new TimerTask() {
            int cnt = 0;

            @Override
            public void run() {
                switch (casenum) {
                    case 0:
                        notification();
                        if(start==1){
                            timer.cancel();
                        }
                        break;
                    case 1:
                        notification();
                        cnt++;
                        if (cnt==1) {
                            cnt=0;
                            timer.cancel();
                        }
                    case 2:
                        notification();
                        cnt++;
                        if (cnt==5) {
                            cnt=0;
                            timer.cancel();
                        }
                }
            }
        };
        timer.schedule(Task, 0, period * 1500);
    }
    int stepTimer = -10;
    int beforeStep = 0;
    int start=0;
    int resetTime=60;

    void checkActivity(int period, int time, int casenum) {
        LOG.debug("check Activity "+stepTimer+" : "+HuamiSupport.HEART_RATE+"  "+HuamiSupport.TOTAL_STEP +" "+(HuamiSupport.TOTAL_STEP - beforeStep));
        if(HuamiSupport.HEART_RATE > 40) {
            stepTimer++;
            if (stepTimer == -1) {
                beforeStep = HuamiSupport.TOTAL_STEP;
            }
            if (stepTimer == 0) {

            } else if (stepTimer < 20) {                //알람 조건 종료
                if (beforeStep != 0 && HuamiSupport.TOTAL_STEP - beforeStep > 10) {
                    start = 1;
                }
            } else if (stepTimer == 30) {                 //알람 종료
                start = 1;
                beforeStep = HuamiSupport.TOTAL_STEP;
            } else if (stepTimer == resetTime) {                 //주기
                start = 0;

                if (beforeStep != 0 && HuamiSupport.TOTAL_STEP - beforeStep < 10) {        //이전과 비교해서 걸음수 체크
                            vibration_timer(period,time,casenum);
                }
                beforeStep = HuamiSupport.TOTAL_STEP;
                stepTimer = -1;
            }
        }
    }





    @RequiresApi(api = Build.VERSION_CODES.O)
    void createNotificationChannel(String channelID, String channelName, int importance){
        if(Build.VERSION.SDK_INT >= (Build.VERSION_CODES.BASE-1)){
            NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(new NotificationChannel(channelID, channelName, importance));
        }
    }

    void createNotification(String channelID, int id, String title, String text, Intent intent){
        PendingIntent pendingIntent = PendingIntent.getActivities(this, 0, new Intent[]{intent}, PendingIntent.FLAG_CANCEL_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelID)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
//                .addAction(R.drawable.ic_launcher_foreground, getString(R.string.action_quit), pendingIntent)
//                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                ;

        NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(id, builder.build());
    }

    public void destroyNotification(int id){
        NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        HuamiSupport.SEND_DATA=1;
        notificationManager.cancel(id);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (isLanguageInvalid) {
            isLanguageInvalid = false;
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterForContextMenu(deviceListView);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MENU_REFRESH_CODE) {
            showFabIfNeccessary();
        }
    }


    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);

        switch (item.getItemId()) {
            case R.id.action_settings:
                Intent settingsIntent = new Intent(this, SettingsActivity.class);
                startActivityForResult(settingsIntent, MENU_REFRESH_CODE);
                return true;
            case R.id.action_debug:
                Intent debugIntent = new Intent(this, DebugActivity.class);
                startActivity(debugIntent);
                return true;
            case R.id.action_data_management:
                Intent dbIntent = new Intent(this, DataManagementActivity.class);
                startActivity(dbIntent);
                return true;
            case R.id.action_blacklist:
                Intent blIntent = new Intent(this, AppBlacklistActivity.class);
                startActivity(blIntent);
                return true;
            case R.id.device_action_discover:
                launchDiscoveryActivity();
                return true;
            case R.id.action_quit:
                GBApplication.quit();
                return true;
            case R.id.donation_link:
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://liberapay.com/Gadgetbridge")); //TODO: centralize if ever used somewhere else
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            case R.id.external_changelog:
                ChangeLog cl = createChangeLog();
                try {
                    cl.getLogDialog().show();
                } catch (Exception ignored) {
                    GB.toast(getBaseContext(), "Error showing Changelog", Toast.LENGTH_LONG, GB.ERROR);
                }
                return true;
            case R.id.about:
                Intent aboutIntent = new Intent(this, AboutActivity.class);
                startActivity(aboutIntent);
                return true;
        }

        return true;
    }

    private ChangeLog createChangeLog() {
        String css = ChangeLog.DEFAULT_CSS;
        css += "body { "
                + "color: " + AndroidUtils.getTextColorHex(getBaseContext()) + "; "
                + "background-color: " + AndroidUtils.getBackgroundColorHex(getBaseContext()) + ";" +
                "}";
        return new ChangeLog(this, css);
    }

    private void launchDiscoveryActivity() {
        startActivity(new Intent(this, DiscoveryActivity.class));
    }

    private void refreshPairedDevices() {
        mGBDeviceAdapter.notifyDataSetChanged();
    }

    private void showFabIfNeccessary() {
        if (GBApplication.getPrefs().getBoolean("display_add_device_fab", true)) {
            fab.show();
        } else {
            if (deviceManager.getDevices().size() < 1) {
                fab.show();
            } else {
                fab.hide();
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void checkAndRequestPermissions() {
        List<String> wantedPermissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.BLUETOOTH);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.BLUETOOTH_ADMIN);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.READ_CONTACTS);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.CALL_PHONE);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.READ_CALL_LOG);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.READ_PHONE_STATE);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.PROCESS_OUTGOING_CALLS) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.PROCESS_OUTGOING_CALLS);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.RECEIVE_SMS);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.READ_SMS);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.SEND_SMS);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.READ_CALENDAR);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_DENIED)
            wantedPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.MEDIA_CONTENT_CONTROL) == PackageManager.PERMISSION_DENIED)
                wantedPermissions.add(Manifest.permission.MEDIA_CONTENT_CONTROL);
        } catch (Exception ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (pesterWithPermissions) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_DENIED) {
                    wantedPermissions.add(Manifest.permission.ANSWER_PHONE_CALLS);
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_DENIED) {
                wantedPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }
        }

        if (!wantedPermissions.isEmpty()) {
            Prefs prefs = GBApplication.getPrefs();
            // If this is not the first run, we can rely on
            // shouldShowRequestPermissionRationale(String permission)
            // and ignore permissions that shouldn't or can't be requested again
            if (prefs.getBoolean("permissions_asked", false)) {
                // Don't request permissions that we shouldn't show a prompt for
                // e.g. permissions that are "Never" granted by the user or never granted by the system
                Set<String> shouldNotAsk = new HashSet<>();
                for (String wantedPermission : wantedPermissions) {
                    if (!shouldShowRequestPermissionRationale(wantedPermission)) {
                        shouldNotAsk.add(wantedPermission);
                    }
                }
                wantedPermissions.removeAll(shouldNotAsk);
            } else {
                // Permissions have not been asked yet, but now will be
                prefs.getPreferences().edit().putBoolean("permissions_asked", true).apply();
            }

            if (!wantedPermissions.isEmpty()) {
                GB.toast(this, getString(R.string.permission_granting_mandatory), Toast.LENGTH_LONG, GB.ERROR);
                ActivityCompat.requestPermissions(this, wantedPermissions.toArray(new String[0]), 0);
                GB.toast(this, getString(R.string.permission_granting_mandatory), Toast.LENGTH_LONG, GB.ERROR);
            }
        }

        /* In order to be able to set ringer mode to silent in GB's PhoneCallReceiver
           the permission to access notifications is needed above Android M
           ACCESS_NOTIFICATION_POLICY is also needed in the manifest */
        if (pesterWithPermissions) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!((NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted()) {
                    GB.toast(this, getString(R.string.permission_granting_mandatory), Toast.LENGTH_LONG, GB.ERROR);
                    startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                }
            }
        }

        // HACK: On Lineage we have to do this so that the permission dialog pops up
        if (fakeStateListener == null) {
            fakeStateListener = new PhoneStateListener();
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            telephonyManager.listen(fakeStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            telephonyManager.listen(fakeStateListener, PhoneStateListener.LISTEN_NONE);
        }
    }

    public void setLanguage(Locale language, boolean invalidateLanguage) {
        if (invalidateLanguage) {
            isLanguageInvalid = true;
        }
        AndroidUtils.setLanguage(this, language);
    }

}

