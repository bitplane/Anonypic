package net.bitplane.android.anonypic;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;
import android.content.Context;
import android.content.Intent;

/**
 * This is the upload activity.
 */
public class AnonypicUploader extends Service {
    
	private int     mHighestID = 0;
	private boolean mIsUploaderRunning = false;
	private Lock    mArrayLock = new ReentrantLock();
	
	/** class which represents an active upload*/
	private class ActiveUpload {
		int ID;
		Uri URI;
		Notification Notification;
		boolean IsCancelled = false;
	}
	
	/** Array of all active uploads*/
	NotificationManager     mNotificationManager  = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
	ArrayList<ActiveUpload> mActiveUploads        = new ArrayList<ActiveUpload>();
	
    public AnonypicUploader() {
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        
        // is this a SEND or CANCEL intent?
        if (intent.getAction().equals("net.bitplane.android.anonypic.SEND")) {
        	createUpload(intent);
        }
        else if (intent.getAction().equals("net.bitplane.android.anonypic.CANCEL_SEND")) {
        	// cancel the upload
        	int cancelID = intent.getData().getPort();
        	cancelUpload(cancelID);
        }
        
        return START_STICKY;
    }
    /** Cancel an upload with the given ID */
    private void cancelUpload(int cancelID) {
    	mArrayLock.lock();
    	try {
	    	for (ActiveUpload u : mActiveUploads)
	    		if (u.ID == cancelID) {
	    			// cancel the upload
	    			u.IsCancelled = true;
	    			
	    			// remove the notification
	    			mNotificationManager.cancel(u.ID);
	    			break; // stop looking!
	    		}
    	}
    	finally {
    		mArrayLock.unlock();
    	}
    }
    
    private void createUpload(Intent intent) {

        // instantiate the notification
        int icon                  = R.drawable.notify; // need smaller notification icon at some point
        Uri dataStreamUri         = (Uri) intent.getExtras().getParcelable(Intent.EXTRA_STREAM);
        String filePath           = dataStreamUri.getPath();
        CharSequence tickerText   = "Uploading " + filePath;
        long when                 = System.currentTimeMillis();
        Notification notification = new Notification(icon, tickerText, when);
    	
    	// Define the Notification's expanded message and Intent (which will cancel it)
    	Context context           = getApplicationContext();
    	CharSequence contentText  = "Press to cancel upload";
    	Intent notificationIntent = new Intent();
    	notificationIntent.setAction("net.bitplane.android.anonypic.CANCEL_SEND");

    	mHighestID++;
    	notificationIntent.setData(Uri.parse("null://null:" + mHighestID));
       	PendingIntent contentIntent = PendingIntent.getService(context, 0, notificationIntent, 0);
    	notification.setLatestEventInfo(context, tickerText, contentText, contentIntent);
    	
    	// notify
    	mNotificationManager.notify(mHighestID, notification);
 
    	// add the item to the upload list
    	ActiveUpload newUpload = new ActiveUpload();
    	newUpload.ID           = mHighestID;
    	newUpload.Notification = notification;
    	newUpload.URI          = dataStreamUri;
    	
    	// lock while mutating the array
    	mArrayLock.lock();
    	mActiveUploads.add(newUpload);
    	mArrayLock.unlock();
    	
    	// if the upload thread is inactive, now would be a good time to start it
    	startUploads();
    }

    private ArrayList<ActiveUpload> getActiveUploads() {
		ArrayList<ActiveUpload> newList = new ArrayList<ActiveUpload>();
		
		// lock while traversing array list
		mArrayLock.lock();
		try {
			for (ActiveUpload u : mActiveUploads)
				if (!u.IsCancelled)
					newList.add(u);
		}
		finally {
			mArrayLock.unlock();
		}
		
		return newList;
	}

    private void clearInactiveUploads() {
		ArrayList<ActiveUpload> newList = new ArrayList<ActiveUpload>();
		
		mArrayLock.lock();
		try {
			for (ActiveUpload u : mActiveUploads)
				if (u.IsCancelled)
					newList.add(u);
			mActiveUploads.removeAll(newList);
		}
		finally {
			mArrayLock.unlock();
		}
    }
    
    private void startUploads() {
    	if (!mIsUploaderRunning) {
    		WorkerThread worker = new WorkerThread();
    		worker.start();
    	}
    }
    
    /**
     * The worker thread runs this
     */
    class WorkerThread extends Thread
    {
    	public void run() {
    		mIsUploaderRunning = true;
	    	try {
	    		ArrayList<ActiveUpload> processList = getActiveUploads(); 
	    		
	    		while (!processList.isEmpty()) {
	    			// upload each of the items
	    			for (ActiveUpload u : processList)
	    				uploadToBayImg(u);
	    			
	    			// clear any cancelled/finished items
	    			clearInactiveUploads();
	    			
	    			// get the new list, in case it's changed
	    			processList = getActiveUploads();
	    		}	    		
	    	}
	    	finally {
	    		mIsUploaderRunning = false;
	    	}
	    }
    		    
		private void uploadToBayImg(ActiveUpload uploadToStart) {
			
			InputStream dataStream; 
			URL connectURL;
			
			String fileName    = "upload";
			String removalCode = "todo-randomize";
			String lineEnd     = "\r\n";
			String twoHyphens  = "--";
			String boundary    = "AnonypicBoundary*asd734bb401!!mdsbc8l120*";
			
			try {
				connectURL = new URL("http://upload.bayimg.com/upload");
			} catch (MalformedURLException e) { return; }
			
			try {
				// create a request
				HttpURLConnection conn = (HttpURLConnection) connectURL.openConnection();
	
				conn.setDoInput(true);
				conn.setDoOutput(true);
				conn.setUseCaches(false);
	
				// send HTTP headers
				
				conn.setRequestMethod("POST");
				conn.setRequestProperty("Connection", "Keep-Alive");
				conn.setRequestProperty("User-Agent", "Anonypic photo sharing app for Android handsets by gaz@bitplane.net");
				conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
	
				DataOutputStream dos = new DataOutputStream( conn.getOutputStream() );
				
				/**
				 * This multipart form consists of three sections: removal code, tags, file data
				 */
	
				dos.writeBytes(twoHyphens + boundary + lineEnd);
				dos.writeBytes("Content-Disposition: form-data; name=\"code\"" + lineEnd);
				dos.writeBytes(lineEnd);
				dos.writeBytes(removalCode + lineEnd);
	
				dos.writeBytes(twoHyphens + boundary + lineEnd);
				dos.writeBytes("Content-Disposition: form-data; name=\"tags\"" + lineEnd);
				dos.writeBytes(lineEnd);
				dos.writeBytes("Anonypic" + lineEnd);
				
				dos.writeBytes(twoHyphens + boundary + lineEnd);
				dos.writeBytes("Content-Disposition: form-data; name=\"uploadedfile\"; filename=\"irrelevant\"" + lineEnd);
				dos.writeBytes(lineEnd);
	
				// buffered file upload
				dataStream = getContentResolver().openInputStream(uploadToStart.URI);		
				int bytesAvailable = dataStream.available();
				int maxBufferSize = 1024;
				int bufferSize = Math.min(bytesAvailable, maxBufferSize);
				byte[] buffer = new byte[bufferSize];	
	
				// reset the notification
				
				int bytesRead = dataStream.read(buffer, 0, bufferSize);
	
				while (bytesRead > 0 && uploadToStart.IsCancelled == false) {
					if (uploadToStart.IsCancelled)
						return;
	
					dos.write(buffer, 0, bufferSize);
					bytesAvailable = dataStream.available();
					bufferSize = Math.min(bytesAvailable, maxBufferSize);
					bytesRead = dataStream.read(buffer, 0, bufferSize);
					// todo: update notification with percentage
					
				}
				
				// send end of multipart block
	
				dos.writeBytes(lineEnd);
				dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
	
				// close streams
				dataStream.close();
				dos.flush();
	
				InputStream is = conn.getInputStream();
				// retrieve the response from server
				int ch;
	
				StringBuffer b = new StringBuffer();
				while( ( ch = is.read() ) != -1 ){
					b.append( (char)ch );
				}
				String s = b.toString(); 
				Log.i("Response",s);
				dos.close();
			}
			catch (IOException ioe) {
				// update notification
				Log.e("ERROR", ioe.getMessage(), ioe);
			}
		}
    }
}