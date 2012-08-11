/*
 * Author:	Ivan Carballo Fernandez (icf1e11@soton.ac.uk) 
 * Project:	CpuFrequencies - Android Service that works as a CPU profiler for the time_in_state file. 
 * Date:	11-08-2012
 * 
 * License: Copyright (C) 2012 Ivan Carballo. 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.byivan.cpufrequencies;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Pattern;

import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

/*
 * This service works as a CPU profiler of the file /sys/devices/system/cpu/cpu<cpu_id>/cpufreq/stats/time_in_state.
 * As the Linux kernel documentation explains, this file  gives the amount of time spent in each of the frequencies 
 * supported by this CPU. The cat output will have "<frequency> <time>" pair in each line,
 * which will mean this CPU spent <time> usertime units of time at <frequency>. Output will have one line for each
 * of the supported frequencies. usertime units here is 10mS (similar to other time exported in /proc).
 * 
 * This service makes a record of the file when started and another record when closed. Then, it calculates the difference
 * between the initial and the final time for each frequency/CPU and stores the results in a CSV file located in
 * <external_storage>/cpu_frequencies/time_in_state_logs_<date>.csv
 * 
 *  This profiler support devices with more than one CPU/core. The CVS file will store different results for each CPU.*/
public class CpuProfilerService extends Service {

	// Separator, ',' for CSV files.
	public static final String SEPARATOR = ",";
	/*
	 * List of HashMaps, every item of the list stores data of a CPU. Therefore,
	 * the size of the List is equal to the number of CPUs. The HashMap key is
	 * the frequency and the value is the time spent in that frequency.
	 */
	private ArrayList<HashMap<String, Long>> initialTimeInFreq = null;
	// Number of CPUs of the device.
	private int numCpus = 1;

	@Override
	public IBinder onBind(Intent intent) {
		// Not used for this kind of service.
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		// Read number of CPUs
		int numCpusTmp = getNumCPUs();
		if (numCpusTmp > 0) {
			numCpus = numCpusTmp;
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// Start profiling. Get initial values of file time_in_state.
		initialTimeInFreq = parseAllTimeInState();
		Log.i(getClass().getName(), "Cpu profiling started");
		return START_STICKY;
	}

	// Read CPU statistics from file time_in_state from all the CPUs.
	private ArrayList<HashMap<String, Long>> parseAllTimeInState() {
		ArrayList<HashMap<String, Long>> timeInFreqCpus = new ArrayList<HashMap<String, Long>>();
		for (int i = 0; i < numCpus; i++) {
			HashMap<String, Long> timeInFreqOneCpu = parseTimeInState(i);
			if (timeInFreqOneCpu != null)
				timeInFreqCpus.add(i, timeInFreqOneCpu);
			else
				Log.e(getClass().getName(), "Error, parseTimeInState() with parameter cpuId=" + i
						+ " has returned null");
		}
		if (timeInFreqCpus.size() > 0)
			return timeInFreqCpus;
		return null;
	}

	@Override
	public void onDestroy() {
		Log.i(getClass().getName(), "Stopping service CpuProfiler.");
		if (initialTimeInFreq != null) {
			// Stop profiling, Read final values.
			ArrayList<HashMap<String, Long>> finalTimeInFreq = parseAllTimeInState();
			if (finalTimeInFreq != null)
				// Write the results in the log file.
				writeToFile(initialTimeInFreq, finalTimeInFreq);
		}
		super.onDestroy();
	}

	/*
	 * This method works out the time difference between the initial and final
	 * readings and saves the results in
	 * <external_storage>/cpu_frequencies/time_in_state_logs_<date>.csv
	 */
	private void writeToFile(ArrayList<HashMap<String, Long>> initialTimeInFreqCpus,
			ArrayList<HashMap<String, Long>> finalTimeInFreqCpus) {
		if (initialTimeInFreqCpus.size() != finalTimeInFreqCpus.size()) {
			Log.e(getClass().getName(),
					"Error, intial and final readings of time_in_state has different number of CPUs");
			return;
		}
		File file = null;
		FileWriter fw = null;
		try {
			// Check external Storage
			String state = Environment.getExternalStorageState();
			if (Environment.MEDIA_MOUNTED.equals(state)) {
				// We can read and write the media
				File path = Environment.getExternalStoragePublicDirectory("cpu_frequencies");
				Calendar rightNow = Calendar.getInstance();
				String date = Integer.toString(rightNow.get(Calendar.DAY_OF_MONTH))
						+ Integer.toString(rightNow.get(Calendar.MONTH))
						+ Integer.toString(rightNow.get(Calendar.YEAR)) + "_"
						+ Integer.toString(rightNow.get(Calendar.HOUR_OF_DAY))
						+ Integer.toString(rightNow.get(Calendar.MINUTE))
						+ Integer.toString(rightNow.get(Calendar.SECOND));
				file = new File(path, "time_in_state_logs_" + date + ".csv");
				path.mkdirs();
				fw = new FileWriter(file);
				// Traverse all the HashMaps for each CPU
				for (int i = 0; i < initialTimeInFreqCpus.size(); i++) {
					HashMap<String, Long> initialTimeInFreq = initialTimeInFreqCpus.get(i);
					HashMap<String, Long> finalTimeInFreq = finalTimeInFreqCpus.get(i);
					if (initialTimeInFreq.size() == finalTimeInFreq.size()) {
						Iterator<String> itr = initialTimeInFreq.keySet().iterator();
						long initialTime = 0;
						long finalTime = 0;
						fw.write("CPU" + SEPARATOR + i + "\n");
						fw.write("Frequency" + SEPARATOR + "Time" + "\n");
						// Traverse frequencies for CPU with ID=i
						while (itr.hasNext()) {
							// The key of the HasMap is the frequency
							String frequency = itr.next();
							initialTime = initialTimeInFreq.get(frequency);
							finalTime = finalTimeInFreq.get(frequency);
							// Calculate difference for a given frequency and
							// CPU
							long resultTime = finalTime - initialTime;
							// Add result to file.
							fw.write(frequency + SEPARATOR + resultTime + "\n");
						}
						fw.write("\n");
					} else {
						Log.e(getClass().getName(),
								"Error, intial and final readings of time_in_state has different number of frequencies");
					}
				}
			} else {
				// Something else is wrong. It may be one of many other
				// states,but all we need to know is we can neither read nor
				// write
				Log.e(getClass().getName(), "Error opening file ");
			}
		} catch (IOException e) {
			// Unable to create file, likely because external storage is
			// not currently mounted.
			Log.e(e.getClass().getName(), e.getMessage(), e);
		} finally {
			if (fw != null)
				try {
					// Closing File Writer
					fw.close();
				} catch (IOException e) {
					Log.e(e.getClass().getName(), e.getMessage(), e);
				}
		}

	}

	// Read CPU statistics from file time_in_state for a given CPU.
	private HashMap<String, Long> parseTimeInState(int cpuId) {
		// Check whether the cpuId is valid.
		if (cpuId < 0 || cpuId > numCpus - 1) {
			Log.e(getClass().getName(), "Error, cpuId parameter of method parseTimeInState is invalid, cpuId=" + cpuId);
			return null;
		}
		BufferedReader in = null;
		HashMap<String, Long> timeInStateCpu = null;
		try {
			/*
			 * Reads the file time_in_state. This gives the amount of time spent
			 * in each of the frequencies supported by this CPU. The cat output
			 * will have "<frequency> <time>" pair in each line, which will mean
			 * this CPU spent <time> usertime units of time at <frequency>.
			 * Output will have one line for each of the supported frequencies.
			 * usertime units here is 10mS (similar to other time exported in
			 * /proc).
			 */
			Process process;
			process = Runtime.getRuntime().exec(
					"cat /sys/devices/system/cpu/cpu" + cpuId + "/cpufreq/stats/time_in_state");
			in = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			timeInStateCpu = new HashMap<String, Long>();
			while ((line = in.readLine()) != null) {
				String[] lines = line.split(" ");
				// Format different from expected, stop method.
				if (lines.length != 2)
					return null;
				// Time for in a frequency
				Long time = Long.valueOf(lines[1]);
				// Put the frequency as key of the HasMap and the time as value.
				timeInStateCpu.put(lines[0], time);
			}
			if (timeInStateCpu.size() <= 0) {
				// when in.readLine() returns null from the begging, that means
				// the file is empty
				Log.e(getClass().getName(), "Error in parseTimeInState, no lines read with in.readLine()");
				return null;
			}
		} catch (Exception e) {
			Log.e(e.getClass().getName(), e.getMessage(), e);
			return null;
		} finally {
			try {
				if (in != null)
					// Close bufferedReader.
					in.close();
			} catch (IOException e) {
				Log.e(e.getClass().getName(), e.getMessage(), e);
			}
		}
		return timeInStateCpu;
	}

	// Returns the number of CPUs of the device or 0 if the number of CPUs can't
	// be read
	private int getNumCPUs() {
		int numCPUs = 0;
		BufferedReader in = null;
		try {
			/*
			 * To find out the number of CPUs it looks for folders inside
			 * /sys/devices/system/cpu/ which name is cpu plus one number from 0
			 * to 9. For example: /sys/devices/system/cpu/cpu0
			 * /sys/devices/system/cpu/cpu1 /sys/devices/system/cpu/cpu2
			 */
			Process process;
			process = Runtime.getRuntime().exec("ls /sys/devices/system/cpu");
			in = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = in.readLine()) != null) {
				String[] lines = line.split(" ");
				for (int i = 0; i < lines.length; i++) {
					String directory = lines[i].trim();
					if (Pattern.matches("cpu[0-9]", directory))
						numCPUs++;
				}
			}
		} catch (Exception e) {
			Log.e(e.getClass().getName(), e.getMessage(), e);
		} finally {
			try {
				if (in != null)
					in.close();
			} catch (IOException e) {
				Log.e(e.getClass().getName(), e.getMessage(), e);
			}
		}
		return numCPUs;
	}

}
