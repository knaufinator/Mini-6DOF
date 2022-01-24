package com.knaufinator.mini6dofcontroller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import com.welie.blessed.BluetoothCentral;

import com.knaufinator.mini6dofcontroller.databinding.FragmentFirstBinding;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.WriteType;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

public class FirstFragment extends Fragment {

    private FragmentFirstBinding binding;
    private ListView listView_device;
    private DeviceAdapter mDeviceAdapter;
    MainActivity mainActivity;
    private Disposable disposableTimer;

    private Handler mHandler;
    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {


        mainActivity = (MainActivity)getActivity();


        binding = FragmentFirstBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        listView_device = (ListView) root.findViewById(R.id.devices);
        mDeviceAdapter = new DeviceAdapter(root.getContext());
        listView_device.setAdapter(mDeviceAdapter);

        mDeviceAdapter.setOnDeviceClickListener(new DeviceAdapter.OnDeviceClickListener() {
          @Override
          public void onConnect(DeviceWrapper bleDevice) {

              try {
                  mainActivity.peripheral = BluetoothHandler.getInstance(getActivity().getApplicationContext()).central.getPeripheral(bleDevice.Mac);
                  BluetoothHandler.getInstance(getActivity().getApplicationContext()).connectPeripheral(mainActivity.peripheral);



              } catch (Exception e) {
                  e.printStackTrace();
              }
          }

          @Override
          public void onDisConnect(final DeviceWrapper bleDevice) {



          }

          @Override
          public void onDetail(DeviceWrapper bleDevice) {

          }
      });


        return root;

    }

    private BroadcastReceiver mNotificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String name = intent.getStringExtra(BluetoothHandler.MINI6DOF_DEVICE_NAME);
            String mac = intent.getStringExtra(BluetoothHandler.MINI6DOF_DEVICE_MAC);
            int state = intent.getIntExtra(BluetoothHandler.MINI6DOF_DEVICE_STATE,0);

            DeviceWrapper deviceWrapper = new DeviceWrapper();
            deviceWrapper.Name =name;
            deviceWrapper.Mac =mac;
            deviceWrapper.ConnectionState = state;

            mDeviceAdapter.addDevice(deviceWrapper);
        }
    };

    @Override
    public  void onResume() {
        super.onResume();
        getActivity().registerReceiver(mNotificationReceiver, new IntentFilter(BluetoothHandler.MINI6DOF));
    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().unregisterReceiver(mNotificationReceiver);
    }

    private void reset()
    {
        binding.Ax.setProgress(2047);
        binding.Ay.setProgress(2047);
        binding.Az.setProgress(2047);
        binding.ARx.setProgress(2047);
        binding.ARy.setProgress(2047);
        binding.ARz.setProgress(2047);
    }



    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        reset();
        mHandler = new Handler();


        binding.button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //search for mini open 6dof ble devices
                BluetoothHandler.getInstance(getActivity().getApplicationContext()).startScan();
            }
        });


        binding.toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {

                    int timer = 50;
                    disposableTimer = Observable.interval(timer, TimeUnit.MILLISECONDS)
                            .map((tick) -> {
                                mHandler.post(() -> {

                                    try {
                                        setPOS();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }

                                });
                                return true;
                            }).subscribe();

                } else {

                    if (disposableTimer != null) {
                        disposableTimer.dispose();
                    }
                }
            }
        });




//        binding.button2.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                //search for mini open 6dof ble devices
//                BluetoothHandler.getInstance(getActivity().getApplicationContext()).stopScan();
//            }
//        });
//

        binding.button3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                setPOS();
            }
        });
    }
    private void setPOS()
    {
        int ax = binding.Ax.getProgress();
        int ay = binding.Ay.getProgress();
        int az = binding.Az.getProgress();
        int aRx = binding.ARx.getProgress();
        int aRy = binding.ARy.getProgress();
        int aRz = binding.ARz.getProgress();

        mainActivity.sendData(ax, ay, az, aRx, aRy, aRz);
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

}