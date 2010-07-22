package net.bitplane.android.anonypic;

import android.content.Context;
import android.content.Intent;

/** The main entry point for sharing an image.
 * 
 * Because intent filters to do with sharing images or links
 * don't work with services, we need this ugly hack job to forward
 * the intent to the service.
 * 
 * @author Gaz Davidson
 */
public class AnonypicUI extends android.app.Activity {

	public AnonypicUI() {
	}
	
	@Override
	public void onStart() {
		super.onStart();
		
		Intent intent = getIntent();
		
		// if this is a share request, forward it to the uploader service
		if (intent.getAction().equals("android.intent.action.SEND")) {
			Context context  = getApplicationContext();
			Intent newIntent = new Intent();
			newIntent.setAction("net.bitplane.android.anonypic.SEND"); // type of message
			newIntent.setData(intent.getData());
			newIntent.putExtras(intent.getExtras()); // contains stream to data
			context.startService(newIntent); // launch the anonypic upload service
			
			finish(); // close the UI
		}
		// otherwise, we'll open the settings page... (todo)
	}
}
