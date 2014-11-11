package com.trevorshp.boattracker;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.format.Time;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

public class MainActivity extends Activity {

	private static final String TAG = "boatTracker";
	private static final String FILETYPE_CSV = "CSV";
	private static final String FILETYPE_KML = "KML";
	private static final String FILETYPE_GPX = "GPX";
	
	private TextView latitudeText;
	private TextView longitudeText;
	private TextView accuracyText;
	
	private double lat;
	private double lon;
	private double acc;
	
	private int lineColor;
	private int lineWidth;

	private boolean logging;
	private boolean waitingForGps;
	private String filetype;
	
	private FileWriter logWriter;
	private File file;

	private Time time;
	
	private LocationManager locMan;
	private LocationListener locationListener;
	private UsbSerialDriver driver;
	private UsbManager usbManager;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SerialInputOutputManager serialIoManager;

    private final SerialInputOutputManager.Listener serialIolistener =
            new SerialInputOutputManager.Listener() {

        @Override
        public void onRunError(Exception e) {
            Log.d(TAG, "Runner stopped.");
        }

        @Override
        public void onNewData(final byte[] data) {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                	MainActivity.this.processData(data);
                }
            });
        }
    };
	
	SharedPreferences sharedPrefs;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		PreferenceManager.setDefaultValues(this, R.xml.pref_general, true);
		sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		latitudeText = (TextView)this.findViewById(R.id.latitude);
		longitudeText = (TextView)this.findViewById(R.id.longitude);
		accuracyText = (TextView)this.findViewById(R.id.accuracy);
		
		lat = 0;
		lon = 0;
		acc = 0;
		
		lineColor = 0xff005bff;
		lineWidth = 5;
		
		logging = false;
		waitingForGps = true;
		
		logWriter = null;
		driver = null;
		
		time = new Time();
		
		// Get UsbManager from Android.
		usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
				
		locMan = (LocationManager)this.getSystemService(LOCATION_SERVICE);
		locationListener = new LocationListener() {
			
			@Override
			public void onStatusChanged(String provider, int status, Bundle extras) {
				if (status == LocationProvider.AVAILABLE){
					((Button)findViewById(R.id.logging_button)).setText(R.string.stop_logging);
					waitingForGps = false;
				}
			}
			
			@Override
			public void onProviderEnabled(String provider) {
				// TODO Auto-generated method stub
				
			}
			
			@Override
			public void onProviderDisabled(String provider) {
				// TODO Auto-generated method stub
				
			}
			
			@Override
			public void onLocationChanged(Location location) {
				//Log.d("Location Update", location.getLatitude() + ", " + location.getLongitude());
				updateLocation(location);
				
			}
		};	

	}
	
	 @Override
    protected void onPause() {
        super.onPause();
        stopIoManager();
        if (driver != null) {
            try {
                driver.close();
            } catch (IOException e) {
                // Ignore.
            }
            driver = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        driver = UsbSerialProber.acquire(usbManager);
        Log.d(TAG, "Resumed, mSerialDevice=" + driver);
        if (driver == null) {
            Log.d(TAG, "No serial device.");
        } else {
            try {
                driver.open();
                Log.d(TAG, "serial device opened");
                driver.setBaudRate(115200);
            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                try {
                    driver.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                driver = null;
                return;
            }
        }
        onDeviceStateChange();
    }

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		switch (item.getItemId()){
		case R.id.action_settings:
			Intent intent = new Intent(this, SettingsActivity.class);
			startActivity(intent);
			return true;
		default:
			return false;
		}
	}
	
	public void updateLocation(Location location){
		lat = location.getLatitude();
		lon = location.getLongitude();
		acc = location.getAccuracy();
		
		latitudeText.setText(Double.toString(lat));
		longitudeText.setText(Double.toString(lon));
		accuracyText.setText("to within about " + Double.toString(acc) + " m");
		
		if (logging && logWriter != null){
			try{
				time.setToNow();
				if (filetype.equals(FILETYPE_CSV)){
					logWriter.write(time.format("%FT%T") + "," + lat + "," + lon + "," + acc + "\n");
				}
				else if (filetype.equals(FILETYPE_KML)){
					logWriter.write("\t\t\t\t" + lon + "," + lat + "," + "0 \n");
				}
				else if (filetype.equals(FILETYPE_GPX)){
					Time timeUTC = new Time(Time.TIMEZONE_UTC);
					timeUTC.setToNow();
					logWriter.write("\t\t\t<trkpt lat=\"" + lat + "\" lon=\"" + lon + "\">\n" +
							"\t\t\t\t<ele>0</ele>\n" +
							"\t\t\t\t<time>" + timeUTC.format("%FT%T.000Z") + "</time>\n" +
							"\t\t\t</trkpt>\n");		
				}				
			}
			catch (IOException e){
				Log.e("boatTracker", e.getMessage());
				Log.i("boatTracker", "could not write coordinates to logfile!");
			}
		}
	}
	
	public void toggleLogging(View view){
		if (!logging){
			filetype = sharedPrefs.getString("file_format", FILETYPE_KML);
			if (externalStorageWriteable()){
				File root = Environment.getExternalStorageDirectory();
				File path = new File(root + "/boatTracker");
				time.setToNow();
				String extension = "." + filetype.toLowerCase();
				String filename = time.format("%a_%m_%d_%Y_%T") + "_logfile" + extension;
				file = new File(path, filename);
				logWriter = null;
				try {
					path.mkdirs();
					logWriter = new FileWriter(file, false);
					if (filetype.equals(FILETYPE_CSV)){
						logWriter.write("timestamp,latitude,longitude,accuracy\n");
					}
					else if (filetype.equals(FILETYPE_KML)){
						logWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n" + 							
								"\t<Placemark>\n" +
								"\t\t<name>" + filename + "</name>\n" +
								"\t\t<description>a track logged on " + time.format("%A %B %e, %Y") + " at " + time.format("%r") + ".</description>\n" + 
								"\t\t<Style>\n" +
								"\t\t\t<LineStyle>\n" + 
								"\t\t\t\t<color>" + Integer.toHexString(lineColor) + "</color>\n" + 
								"\t\t\t\t<width>" + lineWidth + "</width>\n" +
								"\t\t\t</LineStyle>\n" +
								"\t\t</Style>\n" + 
								"\t\t<LineString>\n" +
								"\t\t\t<altitudemode>relativeToGround</altitudemode>\n" +
								"\t\t\t<tesselate>0</tesselate>\n" +
								"\t\t\t<extrude>0</extrude>\n" +
								"\t\t\t<coordinates>\n");
					}
					else if (filetype.equals(FILETYPE_GPX)){
						logWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
								"\t<gpx version=\"1.1\" creator=\"Created by boatTracker.\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:topografix=\"http://www.topografix.com/GPX/Private/TopoGrafix/0/1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd http://www.topografix.com/GPX/Private/TopoGrafix/0/1 http://www.topografix.com/GPX/Private/TopoGrafix/0/1/topografix.xsd\">\n" + 
								"\t<metadata>\n" +
								"\t\t<name>" + filename + "</name>\n" + 
								"\t\t<desc>a track logged on " + time.format("%A %B %e, %Y") + " at " + time.format("%r") + ".</desc>\n" +
								"\t</metadata>\n" +
								"\t<trk>\n" +
								"\t\t<name>" + filename + "</name>\n" + 
								"\t\t<desc>a track logged on " + time.format("%A %B %e, %Y") + " at " + time.format("%r") + ".</desc>\n" +
								"\t\t<extensions><topografix:color>" + Integer.toHexString(lineColor) + "</topografix:color></extensions>\n" + 
								"\t\t<trkseg>\n");
					}

					locMan.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);
					logging = true;
					waitingForGps = true;
					((Button)view).setText(R.string.waiting_to_log);
					Log.d("boatTracker", "logging started on " + path.getAbsolutePath() + "/" + filename);
				} 
				catch (IOException e) {
					Log.e("boatTracker", e.getMessage());
					Log.i("boatTracker", "log file could not be opened for writing!");
				}	
			}
			else{
				Log.e("boatTracker", "could not create log file!  external storage unavailable.");
			}
		}
		else{
			if (logWriter != null){
				try{
					if (filetype.equals(FILETYPE_KML)){
						logWriter.write("\t\t\t</coordinates>\n" +
								"\t\t</LineString>\n" +
								"\t</Placemark>\n" +
								"</kml>");
					}
					else if (filetype.equals(FILETYPE_GPX)){
						logWriter.write("\t\t</trkseg>\n" + 
								"\t</trk>\n" +
								"</gpx>");
					}
					logWriter.close();
					//stop getting location updates
					locMan.removeUpdates(locationListener);
					logging = false;
					((Button)view).setText(R.string.start_logging);
					Log.d("boatTracker", "log file closed");
					
					 // Tell the media scanner about the new file so that it is
			        // immediately available to the user.
			        MediaScannerConnection.scanFile(this, new String[] { file.toString() }, null, null);
				}
				catch(IOException e){
					Log.e("boatTracker", e.getMessage());
					Log.i("boatTracker", "log file could not be closed!");
				}
			}	
		}
	}
	
	public void sendCommand(View view){
		byte[] ledData = {0};
		//are we turning on or off?
		if (((ToggleButton)view).isChecked()){
			ledData[0] = 'n';
		}
		else{
			ledData[0] = 'f';
		}
		
		//write the byte
		try{
			driver.write(ledData, 1000);
		}
		catch (IOException e){
			//ignore
		}
	}
	
	public void toggleUsb(View view){
//		if (driver == null){
//			// Find the first available driver.
//			driver = UsbSerialProber.acquire(usbManager);
//			if (driver != null) {
//			  try {
//				driver.open();
//				Log.d(TAG, "driver opened!");
//				((Button)view).setText(R.string.close_usb);
//			    driver.setBaudRate(115200);
//			    
//			    //byte buffer[] = new byte[16];
//			    //int numBytesRead = driver.read(buffer, 1000);
//			    //Log.d(TAG, "Read " + numBytesRead + " bytes.");
//			  } 
//			  catch (IOException e) {
//				  Log.e(TAG, e.getMessage());
//				  Log.i(TAG, "could not open driver");
//				  try{
//					  driver.close();
//				  }
//				  catch (IOException e2){
//					  //do nothing
//				  }
//			  } 
//			}
//		}
//		else{
//		   try{
//			   driver.close();
//			   Log.d(TAG, "driver closed!");
//			   driver = null;
//			   ((Button)view).setText(R.string.open_usb);
//		   }
//		   catch (IOException e){
//			   Log.e(TAG, e.getMessage());
//			   Log.i(TAG, "could not close driver");
//		   }
//		}
			
	}
	
	
	public void processData(byte[] dataIn){
		int val = 0;
		if (dataIn.length == 2){
			for (int i=0; i<dataIn.length; i++){
				//Log.d(TAG, dataIn[i]+"");
				val = val | ((dataIn[i] & 0xFF) << (8*i));
			}
			((TextView)findViewById(R.id.serial_data)).setText("Serial data: " + val);
		}
	}
	
	private void stopIoManager() {
        if (serialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            serialIoManager.stop();
            serialIoManager = null;
        }
    }

    private void startIoManager() {
        if (driver != null) {
            Log.i(TAG, "Starting io manager ..");
            serialIoManager = new SerialInputOutputManager(driver, serialIolistener);
            executor.submit(serialIoManager);
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }
	
	private boolean externalStorageWriteable(){
		String state = Environment.getExternalStorageState();

		if (state.equals(Environment.MEDIA_MOUNTED)) {
		    // We can read and write the media
		   return true;
		}
		else {
		    // Something else is wrong. It may be one of many other states, but all we need
		    //  to know is we can neither read nor write
		   return false;
		}
	}

}
