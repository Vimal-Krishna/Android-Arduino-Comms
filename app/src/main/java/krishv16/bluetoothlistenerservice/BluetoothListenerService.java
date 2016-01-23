package krishv16.bluetoothlistenerservice;

import android.app.Instrumentation;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Created by krishv16 on 12-Dec-15.
 */
public class BluetoothListenerService extends Service {
    private static final String TAG = BluetoothListenerService.class.getSimpleName();
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static String address = "20:15:08:13:20:94";

    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    private OutputStream outStream = null;
    private InputStream inStream = null;

    Thread workerThread;
    byte[] readBuffer;
    int readBufferPosition;
    int counter;
    volatile boolean stopWorker;

    private boolean isRunning  = false;

    @Override
    public void onCreate() {
        Log.d(TAG, "Entering onCreate");
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        // check state and turn on BT if required
        isRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Entering onStartCommand");

        // Assumption is that BT is turned on at this point and is paired with HC-05
        // Initiate bluetooth connectivity
        BluetoothDevice device = btAdapter.getRemoteDevice(address);

        try {
            btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            btAdapter.cancelDiscovery();
            btSocket.connect();
        } catch (IOException e) {
            Log.d(TAG, "Failed to connect: " + e.getMessage());
        }

        Log.d(TAG, "Connection established");

        try {
            outStream = btSocket.getOutputStream();
            inStream  = btSocket.getInputStream();
        } catch (IOException e) {
            Log.d(TAG, "Failed to open streams: " + e.getMessage());
        }

        sendMessageToArduino("Android is ready");

        // Call the code that creates the worker thread
        readMessageFromArduino();

        return Service.START_STICKY;
    }

    void sendMessageToArduino(String message) {
        message += "\n";
        byte[] msgBuffer = message.getBytes();
        try {
            outStream.write(msgBuffer);
        } catch (IOException e) {
            Log.d(TAG, "Failed to send message to Arduino: " + e.getMessage());
        }
        Log.d(TAG, "Message sent to arduino: " + message);
    }

    void readMessageFromArduino() {
        final Handler handler = new Handler();
        final byte delimiter = 10;

        stopWorker = false;
        readBufferPosition = 0;
        readBuffer = new byte[1024];
        workerThread = new Thread(new Runnable()
        {
            public void run()
            {
                Log.d(TAG, "Reader thread started");
                while(!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    try
                    {
                        int bytesAvailable = inStream.available();
                        if(bytesAvailable > 0)
                        {
                            byte[] packetBytes = new byte[bytesAvailable];
                            inStream.read(packetBytes);
                            for(int i=0;i<bytesAvailable;i++)
                            {
                                byte b = packetBytes[i];
                                if(b == delimiter)
                                {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, "US-ASCII");
                                    readBufferPosition = 0;

                                    handler.post(new Runnable()
                                    {
                                        public void run()
                                        {
                                            Log.d(TAG, "Arduino: " + data);
                                            sendMessageToArduino("Android: Received keyword " + data);

                                            // perform action based on the keyword that was received
                                            // from arduino
                                            String packageName = "";
                                            String intentAction = "";
                                            Intent intent = null;
                                            if (data.compareTo("maps") == 0)
                                            {
                                                packageName =  "com.google.android.apps.maps";
                                                intent = new Intent(Intent.ACTION_VIEW);
                                                intent.setPackage(packageName);
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                                startActivity(intent);
                                            }
                                            else if (data.compareTo("music") == 0)
                                            {
                                                //packageName = "com.google.android.music";
                                                //packageName = "com.miui.player";

                                                intent = new Intent("android.intent.action.MUSIC_PLAYER");
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                                startActivity(intent);

                                            }
                                            else if (data.compareTo("play") == 0)
                                            {
                                                raiseMediaKeyevent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                                            }
                                            else if (data.compareTo("next") == 0)
                                            {
                                                raiseMediaKeyevent(KeyEvent.KEYCODE_MEDIA_NEXT);
                                            }
                                            else if (data.compareTo("prev") == 0)
                                            {
                                                raiseMediaKeyevent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                                            }
                                            else if (data.compareTo("test") == 0)
                                            {
                                                raiseMediaKeyevent(KeyEvent.KEYCODE_NAVIGATE_IN);
                                            }

                                            Log.d(TAG, "Started " + data);

                                        }
                                    });
                                }
                                else
                                {
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                    }
                    catch (IOException ex)
                    {
                        stopWorker = true;
                    }
                }
                Log.d(TAG, "Reader thread is ending");
            }
        });

        workerThread.start();
    }

    void raiseMediaKeyevent(int keycode)
    {
        long eventtime = SystemClock.uptimeMillis();
        Intent downIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        KeyEvent downEvent = new KeyEvent(eventtime, eventtime,
                KeyEvent.ACTION_DOWN, keycode, 0);
        downIntent.putExtra(Intent.EXTRA_KEY_EVENT, downEvent);
        sendOrderedBroadcast(downIntent, null);

        Intent upIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        KeyEvent upEvent = new KeyEvent(eventtime, eventtime,
                KeyEvent.ACTION_UP, keycode, 0);
        upIntent.putExtra(Intent.EXTRA_KEY_EVENT, upEvent);
        sendOrderedBroadcast(upIntent, null);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Entering onBind");
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Entering onDestroy");
        try {
            outStream.flush();
            outStream.close();
            inStream.close();
            btSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        Log.d(TAG, "Closed BT streams");
        stopWorker = false;
        isRunning = false;
        workerThread.interrupt();
    }
}
