package org.microg.nlp.backend.apple;

import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import org.microg.nlp.api.LocationHelper;

import java.util.*;

public class VerifyingWifiLocationCalculator {
	private static final String TAG = VerifyingWifiLocationCalculator.class.getName();

	private static final long ONE_DAY = 24 * 60 * 60 * 1000;
	private static final int MAX_WIFI_RADIUS = 500;
	private static final float ACCURACY_WEIGHT = 50;
	private static final int MIN_SIGNAL_LEVEL = -200;
	private final WifiLocationDatabase database;
	private final String provider;

	public VerifyingWifiLocationCalculator(String provider, WifiLocationDatabase database) {
		this.database = database;
		this.provider = provider;
	}

	private static Set<Set<Location>> divideInClasses(Set<Location> locations, double accuracy) {
		Set<Set<Location>> classes = new HashSet<Set<Location>>();
		for (Location location : locations) {
			boolean used = false;
			for (Set<Location> locClass : classes) {
				if (locationCompatibleWithClass(location, locClass, accuracy)) {
					locClass.add(location);
					used = true;
				}
			}
			if (!used) {
				Set<Location> locClass = new HashSet<Location>();
				locClass.add(location);
				classes.add(locClass);
			}
		}
		return classes;
	}

	private static boolean locationCompatibleWithClass(Location location, Set<Location> locClass, double accuracy) {
		for (Location other : locClass) {
			if ((location.distanceTo(other) - location.getAccuracy() - other.getAccuracy() -
					accuracy) < 0) {
				return true;
			}
		}
		return false;
	}

	private static void combineClasses(Set<Set<Location>> classes, double accuracy) {
		boolean changed = false;
		for (Set<Location> locClass : classes) {
			for (Set<Location> otherLocClass : classes) {
				if (!locClass.equals(otherLocClass)) {
					for (Location location : locClass) {
						if (locationCompatibleWithClass(location, otherLocClass, accuracy)) {
							locClass.addAll(otherLocClass);
							otherLocClass.addAll(locClass);
							changed = true;
						}
					}
				}
			}
		}
		if (changed) {
			combineClasses(classes, accuracy);
		}
	}

	public Location calculate(Set<Location> locations) {
		Set<Set<Location>> locationClasses = divideInClasses(locations, MAX_WIFI_RADIUS);
		combineClasses(locationClasses, MAX_WIFI_RADIUS);
		List<Set<Location>> clsList = new ArrayList<Set<Location>>(locationClasses);
		Collections.sort(clsList, new Comparator<Set<Location>>() {
			@Override
			public int compare(Set<Location> lhs, Set<Location> rhs) {
				return rhs.size() - lhs.size();
			}
		});
		StringBuilder sb = new StringBuilder("Build classes of size:");
		for (Set<Location> set : clsList) {
			sb.append(" ").append(set.size());
		}
		Log.d(TAG, sb.toString());
		if (!clsList.isEmpty()) {
			Set<Location> cls = clsList.get(0);
			if (cls.size() == 1) {
				Location location = cls.iterator().next();
				if (isVerified(location)) {
					Log.d(TAG, "is single class, but verified.");
					return location;
				}
				return null;
			} else if (cls.size() == 2) {
				boolean verified = false;
				for (Location location : cls) {
					if (isVerified(location)) {
						verified = true;
						break;
					}
				}
				if (verified) {
					Log.d(TAG, "is dual class and verified.");
					verify(cls);
				} else {
					Log.d(TAG, "is dual class, but not verified.");
				}
			} else if (cls.size() > 2) {
				Log.d(TAG, "is multi class and auto-verified.");
				verify(cls);
			}
			return combine(cls);
		}
		return null;
	}

	private int getSignalLevel(Location location) {
		return Math.abs(location.getExtras().getInt(LocationRetriever.EXTRA_SIGNAL_LEVEL) - MIN_SIGNAL_LEVEL);
	}

	private Location combine(Set<Location> locations) {
		float minSignal = Integer.MAX_VALUE, maxSignal = Integer.MIN_VALUE;
		for (Location location : locations) {
			minSignal = Math.min(minSignal, getSignalLevel(location));
			maxSignal = Math.max(maxSignal, getSignalLevel(location));
		}
		double totalWeight = 0;
		double latitude = 0;
		double longitude = 0;
		float accuracy = 0;
		double altitudeWeight = 0;
		double altitude = 0;
		long verified = -1;
		for (Location location : locations) {
			if (location != null) {
				double weight = (((float) (getSignalLevel(location)) - minSignal) / (maxSignal - minSignal))
						+ ACCURACY_WEIGHT / Math.max(location.getAccuracy(), ACCURACY_WEIGHT);
				Log.d(TAG, String.format("Using with weight=%f mac=%s signal=%d accuracy=%f latitude=%f longitude=%f", weight, location.getExtras().getString(LocationRetriever.EXTRA_MAC_ADDRESS), location.getExtras().getInt(LocationRetriever.EXTRA_SIGNAL_LEVEL), location.getAccuracy(), location.getLatitude(), location.getLongitude()));
				totalWeight += weight;
				latitude += location.getLatitude() * weight;
				longitude += location.getLongitude() * weight;
				accuracy += location.getAccuracy() * weight;
				if (location.hasAltitude()) {
					altitude += location.getAltitude() * weight;
					altitudeWeight += weight;
				}
				if (location.getExtras().containsKey(LocationRetriever.EXTRA_VERIFIED_TIME)) {
					verified = Math.max(verified, location.getExtras().getLong(LocationRetriever.EXTRA_VERIFIED_TIME));
				}
			}
		}
		Bundle extras = new Bundle();
		extras.putInt("COMBINED_OF", locations.size());
		if (verified != -1) {
			extras.putLong(LocationRetriever.EXTRA_VERIFIED_TIME, verified);
		}
		if (altitudeWeight > 0) {
			return LocationHelper.create(provider, latitude / totalWeight, longitude / totalWeight, altitude / altitudeWeight, (float) (accuracy / totalWeight), extras);
		} else {
			return LocationHelper.create(provider, latitude / totalWeight, longitude / totalWeight, (float) (accuracy / totalWeight), extras);
		}
	}

	private void verify(Set<Location> cls) {
		WifiLocationDatabase.Editor editor = database.edit();
		for (Location location : cls) {
			location.getExtras().putLong(LocationRetriever.EXTRA_VERIFIED_TIME, System.currentTimeMillis());
			editor.put(location);
		}
		editor.end();
	}

	private boolean isVerified(Location location) {
		return location.getExtras().getLong(LocationRetriever.EXTRA_VERIFIED_TIME) > System.currentTimeMillis() - ONE_DAY;
	}

}
