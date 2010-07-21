package net.bitplane.android.anonypic;

import java.io.DataOutputStream;
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
	private String  mAppTag = "Anonypic";
	private int     mStartId;
	
	/** class which represents an active upload*/
	private class ActiveUpload {
		int ID;
		Uri URI;
		Notification Notification;
		boolean IsCancelled = false;
	}
	
	/** Array of all active uploads*/
	NotificationManager     mNotificationManager;
	ArrayList<ActiveUpload> mActiveUploads;
	
    public AnonypicUploader() {    	
    }
    
    @Override
    public void onCreate() {
    	Context context       = getApplicationContext();
    	mNotificationManager  = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    	mActiveUploads        = new ArrayList<ActiveUpload>();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onStart(Intent intent, int startId) {
    	mStartId = startId;
    	if (intent != null) {
	        // is this a SEND or CANCEL intent?
	        if (intent.getAction().equals("net.bitplane.android.anonypic.SEND")) {
	        	createUpload(intent);
	        }
	        else if (intent.getAction().equals("net.bitplane.android.anonypic.CANCEL_SEND")) {
	        	// cancel the upload
	        	int cancelID = intent.getData().getPort();
	        	cancelUpload(cancelID);
	        }
    	}
    }
    /** Cancel an upload with the given ID */
    private void cancelUpload(int cancelID) {
    	synchronized(mArrayLock) {
	    	for (ActiveUpload u : mActiveUploads)
	    		if (u.ID == cancelID) {
	    			// cancel the upload
	    			u.IsCancelled = true;
	    			
	    			// remove the notification
	    			mNotificationManager.cancel(u.ID);
	    			break; // stop looking!
	    		}
    	}
    }
    
    private void createUpload(Intent intent) {

        // instantiate the notification
        int icon                  = R.drawable.notify; // need smaller notification icon at some point
        Uri dataStreamUri         = (Uri) intent.getExtras().getParcelable(Intent.EXTRA_STREAM);
        String filePath           = dataStreamUri.getPath();
        CharSequence tickerText   = getString(R.string.upload_queued);
        long when                 = System.currentTimeMillis();
        Notification notification = new Notification(icon, tickerText, when);
    	
    	// Define the Notification's expanded message and Intent (which will cancel it)
    	Context context           = getApplicationContext();
    	CharSequence contentText  = getString(R.string.press_to_cancel);
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
    	synchronized (mArrayLock) {
    		mActiveUploads.add(newUpload);
    	}
    	// if the upload thread is inactive, now would be a good time to start it
    	startUploads();
    }

    private ArrayList<ActiveUpload> getActiveUploads() {
		ArrayList<ActiveUpload> newList = new ArrayList<ActiveUpload>();
		
		// lock while traversing array list
		synchronized (mArrayLock) {
			for (ActiveUpload u : mActiveUploads)
				if (!u.IsCancelled)
					newList.add(u);
		}
		
		return newList;
	}

    private void clearInactiveUploads() {
		ArrayList<ActiveUpload> newList = new ArrayList<ActiveUpload>();
		
		synchronized (mArrayLock) {
			for (ActiveUpload u : mActiveUploads)
				if (u.IsCancelled)
					newList.add(u);
			mActiveUploads.removeAll(newList);
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
    		Log.d(mAppTag, "Upload thread started");
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
	    	Log.d(mAppTag, "All uploads complete, upload thread exiting");
	    	stopSelf(mStartId);
	    }
    		    
		private void uploadToBayImg(ActiveUpload uploadToStart) {
			
			InputStream dataStream; 
			URL connectURL;
			double randCode    = Math.random();
			String fileName    = "upload";
			String removalCode = "removal denied " + String.valueOf(randCode);
			String lineEnd     = "\r\n";
			String twoHyphens  = "--";
			String boundary    = "----AnonypicBoundary*asd734bb401";
			String codeHeader  = "Content-Disposition: form-data; name=\"code\"" + lineEnd;
			String tagsHeader  = "Content-Disposition: form-data; name=\"tags\"" + lineEnd;
			String fileHeader  = "Content-Disposition: form-data; name=\"file\"; filename=\"Anonypic for Android mobiles\"" + lineEnd;
			String tags        = "Anonypic";
			
			try {
				connectURL = new URL("http://upload.bayimg.com/upload");
			} catch (MalformedURLException e) { return; }
			
			try {
				// create a request
				HttpURLConnection conn = (HttpURLConnection) connectURL.openConnection();
	
				conn.setDoInput(true);
				conn.setDoOutput(true);
				conn.setUseCaches(false);
	
				// calculate content length
				
				dataStream = getContentResolver().openInputStream(uploadToStart.URI);		
				int fileSize = dataStream.available();
				
				int boundaryLen = twoHyphens.length() + boundary.length() + lineEnd.length();
				
				int reqSize = boundaryLen + codeHeader.length() + lineEnd.length() + removalCode.length() + lineEnd.length() // removal code
							+ boundaryLen + tagsHeader.length() + lineEnd.length() + tags.length() + lineEnd.length() // tags
							+ boundaryLen + fileHeader.length() + lineEnd.length() + fileSize // file data
							+ lineEnd.length() + twoHyphens.length() + boundaryLen; // end boundary
				
				// send HTTP headers
				conn.setRequestMethod("POST");
				conn.setRequestProperty("Connection", "Keep-Alive");
				conn.setRequestProperty("User-Agent", "Anonypic photo sharing app for Android handsets");
				conn.setRequestProperty("Referer", "http://bayimg.com/");
				conn.setRequestProperty("Content-Length", String.valueOf(reqSize));
				conn.setRequestProperty("Cache-Control", "max-age=0");
				conn.setRequestProperty("Origin", "http://bayimg.com");
				conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
				conn.setRequestProperty("Accept", "application/xml,application/xhtml+xml,text/html;q=0.9,text/plain;q=0.8,image/png,*/*;q=0.5");				
				
				DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
				
				/**
				 * This multipart form consists of three sections: removal code, tags, file data
				 */
	
				dos.writeBytes(twoHyphens + boundary + lineEnd);
				dos.writeBytes(codeHeader);
				dos.writeBytes(lineEnd);
				dos.writeBytes(removalCode + lineEnd);
	
				dos.writeBytes(twoHyphens + boundary + lineEnd);
				dos.writeBytes(tagsHeader);
				dos.writeBytes(lineEnd);
				dos.writeBytes(tags + lineEnd);
				
				dos.writeBytes(twoHyphens + boundary + lineEnd);
				dos.writeBytes(fileHeader);
				dos.writeBytes(lineEnd);
	
				// buffered file upload
				int maxBufferSize = 1024;
				int bytesAvailable = fileSize;
				int bufferSize = Math.min(fileSize, maxBufferSize);
				byte[] buffer = new byte[bufferSize];	
	
				// reset the notification
				
				int bytesRead   = dataStream.read(buffer, 0, bufferSize);
				int percent     = 0; 
				int lastPercent = -2;
				
				while (bytesRead > 0 && uploadToStart.IsCancelled == false) {
					if (uploadToStart.IsCancelled)
						return;
	
					dos.write(buffer, 0, bufferSize);
					bytesAvailable = dataStream.available();
					bufferSize = Math.min(bytesAvailable, maxBufferSize);
					bytesRead = dataStream.read(buffer, 0, bufferSize);
					
					percent = (int)(100.0f * ( ((float)fileSize - (float)bytesAvailable) / (float)fileSize));
					
					if (percent > lastPercent + 1) {
						// update notification with percentage
						uploadToStart.Notification.setLatestEventInfo(getApplicationContext(), 
								  getString(R.string.uploading), 
								  String.valueOf(percent) + "%", 
								  uploadToStart.Notification.contentIntent);
						mNotificationManager.notify(uploadToStart.ID, uploadToStart.Notification);
						lastPercent = percent;
					}
				}
				// update notification with percentage
				uploadToStart.Notification.setLatestEventInfo(getApplicationContext(), 
						  getString(R.string.anonymizing), 
						  getString(R.string.anonymizing_info), 
						  uploadToStart.Notification.contentIntent);
				mNotificationManager.notify(uploadToStart.ID, uploadToStart.Notification);
				lastPercent = percent;

				
				// send end of multipart block
	
				dos.writeBytes(lineEnd);
				dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
	
				// close streams
				dataStream.close();
				dos.flush();
				dos.close();
				
				InputStream is = conn.getInputStream();

				// retrieve the response from server
				int ch;
	
				StringBuffer b = new StringBuffer();
				while( ( ch = is.read() ) != -1 ){
					b.append( (char)ch );
				}
				String s = b.toString(); 
				//Log.d(mAppTag, s);
				
				String leftBoundary  = "input type=\"text\" value=\"";
				String rightBoundary = "\" size=\"50\"";
				if (!s.contains(leftBoundary))
					throw new Exception("Bayimg didn't return a link");
				int start = s.indexOf(leftBoundary) + leftBoundary.length();
				String urlString = s.substring(start, s.indexOf(rightBoundary, start));
				
				Log.d(mAppTag, urlString);

				Intent browserIntent = new Intent("android.intent.action.VIEW", Uri.parse(urlString));
				PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, browserIntent, 0);
				uploadToStart.Notification.setLatestEventInfo(getApplicationContext(), 
															  getString(R.string.upload_complete), 
															  urlString, 
															  pendingIntent);
				uploadToStart.Notification.flags |= Notification.FLAG_AUTO_CANCEL;
				
				mNotificationManager.notify(uploadToStart.ID, uploadToStart.Notification);
		    	
			}
			catch (Exception e) {
				// log the error message
				Log.e(mAppTag, e.getMessage(), e);

				Intent browserIntent = new Intent("android.intent.action.VIEW", uploadToStart.URI);
				PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, browserIntent, 0);
				
				// update the notification
				uploadToStart.Notification.setLatestEventInfo(getApplicationContext(), 
															  getString(R.string.upload_failed), 
															  getString(R.string.upload_failed_info), 
															  pendingIntent);
				uploadToStart.Notification.flags |= Notification.FLAG_AUTO_CANCEL;
				
				mNotificationManager.notify(uploadToStart.ID, uploadToStart.Notification);
			}
			// end the upload
			uploadToStart.IsCancelled = true;
		}
    }
}
