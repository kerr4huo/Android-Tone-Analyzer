/* Copyright 2011 Google Inc.
 *
 *Licensed under the Apache License, Version 2.0 (the "License");
 *you may not use this file except in compliance with the License.
 *You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *Unless required by applicable law or agreed to in writing, software
 *distributed under the License is distributed on an "AS IS" BASIS,
 *WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *See the License for the specific language governing permissions and
 *limitations under the License.
 *
 * @author Stephen Uhler
 * modified by Ke Huo at 2015 Mar.
 * For class project Tone analyzer use only
 */

package com.google.corp.productivity.specialprojects.android.samples.fft;

import com.google.corp.productivity.specialprojects.android.fft.RealDoubleFFT;
import com.google.corp.productivity.specialprojects.android.samples.fft.AnalyzeView.Ready;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.Color;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Audio "FFT" analyzer.
 * @author suhler@google.com (Stephen Uhler)
 * Modified by Ke Huo at 2015 Mar.
 * For class project Tone analyzer use only
 */

public class AnalyzeActivity extends Activity implements OnLongClickListener, OnClickListener,
      Ready, OnSharedPreferenceChangeListener {
  static final String TAG="audio";
  private final static float MEAN_MAX = 16384f;   // Maximum signal value
  private final static int AGC_OFF = MediaRecorder.AudioSource.VOICE_RECOGNITION;

  private int fftBins = 4096;
  private int sampleRate = 16000;
  private int updateMs = 150;
  private AnalyzeView graphView;
  private Looper samplingThread;

  private boolean showLines;
  private boolean isTesting = false;
  private boolean isPaused = false;
  private boolean isMeasure = true;
  
  double maxAmpFreq = Double.NaN, maxAmpDB = Double.NaN;
  double minFreq = 200;
  int bufferSize = 20;
  int maxAmpFreqOverall = 0;
  int[] maxAmpFreqArray = new int[bufferSize];
  int maxAmpFreqIndex = 0;
  boolean ifToneDetected = false;
  
  TextView freqTextView;
  
  int[] maxAmpDBArray = new int[500];
  //ArrayList< HashMap< Double, Integer > > maxAmpFreqMapArray = new ArrayList< HashMap< Double, Integer > > ();
  //HashMap<Double, Integer> maxAmpFreqMap = new HashMap<Double, Integer>();
  //ArrayList<Double> maxAmpFreqArray = new ArrayList <Double> ();
  //ArrayList<Integer> maxAmpFreqNumArray = new ArrayList <Integer> ();

  Handler mHandler = new Handler();
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);
    applyPreferences(getPreferences(), null);
    getPreferences().registerOnSharedPreferenceChangeListener(this);
    graphView = (AnalyzeView) findViewById(R.id.plot);
    SelectorText st = (SelectorText) findViewById(R.id.sampling_rate);
    st.setValues(validateAudioRates(st.getValues()));
    visit((ViewGroup) graphView.getRootView(), new Visit() {
      @Override
      public void exec(View view) {
        view.setOnLongClickListener(AnalyzeActivity.this);
        view.setOnClickListener(AnalyzeActivity.this);
        ((TextView) view).setFreezesText(true);
      }
    }, "select");
    
    initArray(maxAmpFreqArray);
    initArray(maxAmpDBArray);
    
    freqTextView = (TextView) findViewById(R.id.maxAmpFreq);
    
    Runnable runnable = new Runnable() {
        @Override
        public void run() {                
            {   
                if (ifToneDetected){
                	freqTextView.setText(
              			"maxAmpFreq = " + String.valueOf(maxAmpFreqOverall) + "HZ");
                	freqTextView.setTextColor(Color.rgb(200,0,0));
                } else {
                	freqTextView.setText(
                    			"maxAmpFreq = " + String.valueOf(maxAmpFreq) + "Hz");
                	freqTextView.setTextColor(Color.rgb(0,200,0));
                }
                
                mHandler.postDelayed(this, 10);
                
            }
        }
    };        
    mHandler.post(runnable);
    
  }

  /**
   * Run processClick() for views, transferring the state in the textView to our
   * internal state, then begin sampling and processing audio data
   */

  @Override
  protected void onResume() {
    super.onResume();
    visit((ViewGroup) graphView.getRootView(), new Visit() {
      @Override
      public void exec(View view) {
        processClick(view);
      }
    }, "select");
    graphView.setReady(this);

    samplingThread = new Looper();
    samplingThread.start();
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
  }

  @Override
  public void onRestoreInstanceState(Bundle bundle) {
    super.onRestoreInstanceState(bundle);
  }

  @Override
  protected void onPause() {
    super.onPause();
    samplingThread.finish();
  }

  @Override
  protected void onDestroy() {
    getPreferences().unregisterOnSharedPreferenceChangeListener(this);
    super.onDestroy();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
   Log.i(TAG, key + "=" + prefs);
   applyPreferences(prefs, key);
  }

  public static class MyPreferences extends PreferenceActivity {
    @Override
    public void onCreate(Bundle state) {
      super.onCreate(state);
      addPreferencesFromResource(R.xml.preferences);
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
      MenuInflater inflater = getMenuInflater();
      inflater.inflate(R.menu.info, menu);
      return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
      Log.i(TAG, item.toString());
      switch (item.getItemId()) {
      case R.id.info:
        showInstructions();
        return true;
      case R.id.settings:
        Intent settings = new Intent(getBaseContext(), MyPreferences.class);
        startActivity(settings);
        return true;
      default:
          return super.onOptionsItemSelected(item);
      }
  }

  private void showInstructions() {
    TextView tv = new TextView(this);
    tv.setMovementMethod(new ScrollingMovementMethod());
    tv.setText(Html.fromHtml(getString(R.string.instructions_text)));
    new AlertDialog.Builder(this)
      .setTitle(R.string.instructions_title)
      .setView(tv)
      .setNegativeButton(R.string.dismiss, null)
      .create().show();
  }

  private SharedPreferences getPreferences() {
    return PreferenceManager.getDefaultSharedPreferences(this);
  }

  private void applyPreferences(SharedPreferences prefs, String pref) {
    if (pref == null || pref.equals("showLines")) {
      showLines = prefs.getBoolean("showLines", false);
    }
    if (pref == null || pref.equals("refreshRate")) {
      updateMs = 1000 / Integer.parseInt(prefs.getString(pref, "5"));
    }
    if (pref == null || pref.equals("fftBins")) {
      fftBins = Integer.parseInt(prefs.getString("fftBins", "4096"));
    }
    if (pref == null || pref.equals("sampleRate")) {
      sampleRate = Integer.parseInt(prefs.getString("sampleRate", "8000"));
    }
  }
  
  /**
   * TODO: add button-specific help on longclick
   */

  @Override
  public boolean onLongClick(View view) {
    vibrate(300);
    Log.i(TAG, "long click: " + view.toString());
    return true;
  }

  @Override
  public void onClick(View v) {
    vibrate(50);
    if (processClick(v)) {
      reRecur();
      updateAllLabels();
    }
  }

  private void reRecur() {
    samplingThread.finish();
    try {
      samplingThread.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    samplingThread = new Looper();
    samplingThread.start();
  }

  /**
   * Process a click on one of our selectors.
   * @param v   The view that was clicked
   * @return    true if we need to update the graph
   */

  public boolean processClick(View v) {
    String value = ((TextView) v).getText().toString();
    if (v.getId() == R.id.test) {
      isTesting = value.equals("test");
      // Log.i(TAG, "Click: test=" + isTesting);
      return false;
    }
    boolean pause = false;
    if (v.getId() == R.id.run) {
        pause = value.equals("stop");
        if (isPaused != pause) {
          isPaused = pause;
          if (samplingThread != null) {
            samplingThread.setPause(isPaused);
          }
        }

        
        return false;
    }
    if (isPaused) {
    	return false;
    }
    if (v.getId() == R.id.bins) {
        fftBins = Integer.parseInt(value);
    } else if (v.getId() == R.id.sampling_rate) {
        sampleRate = Integer.parseInt(value);
        RectF bounds = graphView.getBounds();
        bounds.right = sampleRate / 2;
        graphView.setBounds(bounds);
    } else if (v.getId() == R.id.db) {
        RectF bounds = graphView.getBounds();
        bounds.bottom = Integer.parseInt(value);
        graphView.setBounds(bounds);
    }
    
    return true;
  }

public class popularElement{
	private int element;
	private int repeatCount;
	private int avg;
	
	popularElement(int e, int c, int a){
		this.element = e;
		this.repeatCount = c;
		this.avg = a;
	}
	
}
  
private popularElement findPolularElement(int[] a) { 
  int count = 1, tempCount;
  int popular = a[0];
  int sum = a[0];
  int temp = 0;
  
  for (int i = 0; i < (a.length - 1); i++)
  {
	sum = sum + a[i + 1];
    temp = a[i];
    tempCount = 0;
    
    for (int j = 1; j < a.length; j++)
    {
      
      if (temp > a[j] - 5 && temp < a[j] + 5)
        tempCount++;
    }
    if (tempCount > count)
    {
      popular = temp;
      count = tempCount;
    }
  }
  //Log.i("avg", String.valueOf(sum));
  int avg = (int)(sum/a.length);
	  	  
  return new popularElement(popular, count, avg);
}

private void initArray(int[] myArray){
	
	for (int i = 0; i < myArray.length; i ++ ){
		myArray[i] = 0;
	}
	
}

private void updateAllLabels() {
    refreshCursorLabel();
    refreshMaxFreqLabel();
    refreshMinFreqLabel();
  }

  private void refreshCursorLabel() {
    double f = graphView.getX();
    ((TextView) findViewById(R.id.freq_db)).setText(
        Math.round(f) + "hz\n" + Math.round(graphView.getY()) + "db");
  }

  private void refreshMinFreqLabel() {
      ((TextView) findViewById(R.id.min)).setText(Math.round(graphView.getMin()) + "hz");
  }
  private void refreshMaxFreqLabel() {
      ((TextView) findViewById(R.id.max)).setText(Math.round(graphView.getMax()) + "hz");
  }

  /**
   * recompute the spectral "chart"
   * @param data    The normalized fft output
   */

  public void recompute(double[] data) {
    graphView.recompute(data, 1, data.length / 2, showLines);
    graphView.invalidate();
  }


  /**
   * Convert our samples to double for fft.
   */
  private static double[] shortToDouble(short[] s, double[] d) {
    for (int i = 0; i < d.length; i++) {
      d[i] = s[i];
    }
    return d;
  }

  /**
   * Compute db of bin, where "max" is the reference db
   * @param r Real part
   * @param i complex part
   */
  private static double db2(double r, double i, double maxSquared) {
    return 5.0 * Math.log10((r * r + i * i) / maxSquared);
  }

  /**
   * Convert the fft output to DB
   */

  static double[] convertToDb(double[] data, double maxSquared) {
    data[0] = db2(data[0], 0.0, maxSquared);
    int j = 1;
    for (int i=1; i < data.length - 1; i+=2, j++) {
      data[j] = db2(data[i], data[i+1], maxSquared);
    }
    data[j] = data[0];
    return data;
  }

  /**
   * Verify the supplied audio rates are valid!
   * @param requested
   */
  private static String[] validateAudioRates(String[] requested) {
    ArrayList<String> validated = new ArrayList<String>();
    for (String s : requested) {
      int rate = Integer.parseInt(s);
      if (AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT) != AudioRecord.ERROR_BAD_VALUE) {
        validated.add(s);
      }
    }
    return validated.toArray(new String[0]);
  }
  /**
   * Read a snapshot of audio data at a regular interval, and compute the FFT
   * @author suhler@google.com 
   */

  public class Looper extends Thread {
    AudioRecord record;
    int minBytes;
    long baseTimeMs;
    boolean isRunning = true;
    boolean isPaused1 = false;
    // Choose 2 arbitrary test frequencies to verify FFT operation
    DoubleSineGen sineGen1 = new DoubleSineGen(300, sampleRate, MEAN_MAX / 10);
    DoubleSineGen sineGen2 = new DoubleSineGen(100.0, sampleRate, MEAN_MAX / 4.5);
    double[] tmp = new double[fftBins];
    
    // Timers
    private int loops = 0;

    public Looper() {
      minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT);
      minBytes = Math.max(minBytes, fftBins);
      // VOICE_RECOGNITION: use the mic with AGC turned off!
      record =  new AudioRecord(AGC_OFF, sampleRate,
          AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,  minBytes);
      Log.d(TAG, "Buffer size: " + minBytes + " (" + record.getSampleRate() + "=" + sampleRate + ")");
    }

    @Override
    public void run() {
      final double[] fftData = new double[fftBins];
      RealDoubleFFT fft = new RealDoubleFFT(fftBins);
      double scale = MEAN_MAX * MEAN_MAX * fftBins * fftBins / 2d;
      short[] audioSamples = new short[minBytes];
      record.startRecording();

      baseTimeMs = SystemClock.uptimeMillis();
      while(isRunning) {
        loops++;
        baseTimeMs += updateMs;
        int delay = (int) (baseTimeMs - SystemClock.uptimeMillis());
        if (delay < 20) {
          Log.i(TAG, "wait: " + delay);
        }
        try {
          Thread.sleep(delay < 10 ? 10 : delay);
        } catch (InterruptedException e) {
          // Log.i(TAG, "Delay interrupted");
          continue;
        }

        if (isTesting) {
          sineGen1.getSamples(fftData);
          sineGen2.addSamples(fftData);
        } else {
          record.read(audioSamples, 0, minBytes);
          shortToDouble(audioSamples, fftData);
        }
        if (isPaused1) {
          continue;
        }
        fft.ft(fftData);
        convertToDb(fftData, scale);
        calculatePeak(fftData);
        update(fftData);

      }
      Log.i(TAG, "Releasing Audio");
      record.stop();
      record.release();
      record = null;
    }

    public void calculatePeak(double[] data) {
        // Find and show peak amplitude
    	maxAmpDB = -100;
        maxAmpFreq = 0;
        int freqId = 0;
        int start = (int)Math.round( minFreq*fftBins/sampleRate);
        
        for (int i = start; i < data.length / 2; i++) {  // skip the direct current term
        	
        	if (data[i] > maxAmpDB) {
            maxAmpDB  = data[i];
            maxAmpFreq = i;
            freqId = i;
          }
        }

        maxAmpFreq = maxAmpFreq * sampleRate / fftBins;

        // Slightly better peak finder
        // The peak around spectrumDB should look like quadratic curve after good window function
        // a*x^2 + b*x + c = y
        // a - b + c = x1
        //         c = x2
        // a + b + c = x3
        if (sampleRate / fftBins < maxAmpFreq && maxAmpFreq < sampleRate/2 - sampleRate / fftBins) {
          int id = freqId;
          double x1 = data[id-1];
          double x2 = data[id];
          double x3 = data[id+1];
          double c = x2;
          double a = (x3+x1)/2 - x2;
          double b = (x3-x1)/2;
          if (a < 0) {
            double xPeak = -b/(2*a);
            if (Math.abs(xPeak) < 1) {
              maxAmpFreq += xPeak * sampleRate / fftBins;
              maxAmpDB = (4*a*c - b*b)/(4*a);
            }
          }
        }
        
        maxAmpFreq = Math.round(maxAmpFreq);
        maxAmpFreqArray[maxAmpFreqIndex] = (int)maxAmpFreq;
        maxAmpFreqIndex = (maxAmpFreqIndex + 1) % bufferSize;
        
        popularElement myPop = findPolularElement(maxAmpFreqArray);

        Log.i("maxAmpFreq", String.valueOf(myPop.repeatCount));
        Log.i("avg", String.valueOf(myPop.avg));
        Log.i("avg", String.valueOf(myPop.element));
        
        if (myPop.repeatCount > bufferSize* 0.6){
        	maxAmpFreqOverall = myPop.element;
        	if (Math.abs(myPop.avg - myPop.element) < 10){
        		if (maxAmpFreqOverall > 200){
            		ifToneDetected = true;
            	}else {
            		ifToneDetected = false;
            	}        		
        	}else {
        		ifToneDetected = false;
        	}   
        }else {
        	ifToneDetected = false;
        }

        
    }
    
  
    private void update(final double[] data) {
      AnalyzeActivity.this.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          AnalyzeActivity.this.recompute(data);
        }
      });
    }
    
    public void setPause(boolean pause) {
      this.isPaused1 = pause;
    }

    public void finish() {
      isRunning=false;
      interrupt();
    }
  }

  private void vibrate(int ms) {
    ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(ms);
  }

  /**
   * Visit all subviews of this view group and run command
   * @param group   The parent view group
   * @param cmd     The command to run for each view
   * @param select  The tag value that must match. Null implies all views
   */

  private void visit(ViewGroup group, Visit cmd, String select) {
    exec(group, cmd, select);
    for (int i = 0; i < group.getChildCount(); i++) {
      View c = group.getChildAt(i);
      if (c instanceof ViewGroup) {
        visit((ViewGroup) c, cmd, select);
      } else {
        exec(c, cmd, select);
      }
    }
  }

  private void exec(View v, Visit cmd, String select) {
    if (select == null || select.equals(v.getTag())) {
        cmd.exec(v);
    }
  }

  /**
   * Interface for view heirarchy visitor
   */
  interface Visit {
    public void exec(View view);
  }

  /**
   * The graph view size has been determined - update the labels accordingly.
   */
  @Override
  public void ready() {
    updateAllLabels();
  }
}