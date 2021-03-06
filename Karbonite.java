import java.util.Iterator;
import java.util.LinkedList;

import bc.*;

/*
 * Stores up to date karbonite data in two formats
 * First a list of all locations with karbonite
 * Secondly an array of karbonite values indexed by [x][y]
 */
public class Karbonite {
	GameController gc;
	private long[][] karboniteAt;
	private LinkedList<MapLocation> karboniteLocations;
	private int[] maxWorkers; //How many workers we need to mine each zone
	private MapCache mapCache;
	private int remaining = 0; //The total karbonite potentially left on the map
	
	public Karbonite(GameController g, MapCache mc, int zones) {
		gc = g;
		Planet	myPlanet = gc.planet();
		PlanetMap map = gc.startingMap(myPlanet);
		int w = (int) map.getWidth(), h = (int) map.getHeight();
		int[] turnsToMine = new int[zones];
		maxWorkers = new int[zones];
		mapCache = mc;
		remaining = 0;
		
		karboniteAt = new long[w][h];
		karboniteLocations = new LinkedList<MapLocation>();
		for (int x = 0; x<map.getWidth(); x++) {
			for (int y=0; y<map.getHeight(); y++) {
				MapLocation m = mc.loc(x, y);
				karboniteAt[x][y] = map.initialKarboniteAt(m);
				remaining += karboniteAt[x][y];
				if (karboniteAt[x][y] > 0) {
					karboniteLocations.add(m);
					turnsToMine[mc.zone(x, y)] += (karboniteAt[x][y] + 2) / 3;
				}   
			}
		}
    	
		int minWorkers = (karboniteLocations.size() == 0)?3:6;
		for (int z=0; z<zones; z++) {
	    	maxWorkers[z] = Math.max(minWorkers, turnsToMine[z] / 100);
		}
	}
	
	public long karboniteAt(int x, int y) {
		return karboniteAt[x][y];
	}
	
	public long karboniteAt(MapLocation k) {
		return karboniteAt[k.getX()][k.getY()];
	}
	
	public LinkedList<MapLocation> locations() {
		return karboniteLocations;
	}
	
	public int maxWorkers(int zone) {
		return maxWorkers[zone];
	}
	
	public int remaining() {
		return remaining;
	}
	
	/*
	 * Update all currently known karbonite values
	 */
	public void update(MapState ms) {
		int zones = maxWorkers.length;
		int[] turnsToMine = new int[zones];
		remaining = 0;
		
		for (Iterator<MapLocation> iterator = karboniteLocations.iterator(); iterator.hasNext();) {
    	    MapLocation m = iterator.next();   	    
    		if (ms.visible(m.getX(), m.getY())) {
    			long k = gc.karboniteAt(m);
    			if (k == 0)
    				iterator.remove();
    			karboniteAt[m.getX()][m.getY()] = k;   			
    		}
    		turnsToMine[mapCache.zone(m)] += ((karboniteAt[m.getX()][m.getY()] + 2) / 3);
    		remaining += karboniteAt[m.getX()][m.getY()];
    	}	
    	
    	if (gc.planet() == Planet.Mars) {
    		long currentRound = gc.round();
    		if (gc.asteroidPattern().hasAsteroid(currentRound)) {
    			MapLocation strike = gc.asteroidPattern().asteroid(currentRound).getLocation();
    			karboniteLocations.add(strike);
    			long k = gc.asteroidPattern().asteroid(currentRound).getKarbonite();
    			int x = strike.getX(), y = strike.getY();
    			karboniteAt[x][y] += k;
    			turnsToMine[mapCache.zone(strike)] += ((k + 2) / 3);
    			remaining += k;
    		}
    	}
    	
    	int minWorkers = (karboniteLocations.size() == 0)?3:6;
    	for (int z=0; z<zones; z++) {
	    	maxWorkers[z] = Math.max(minWorkers, turnsToMine[z] / 100);
		}
	}
	
	public void harvest(MapLocation here, Unit u) {
		karboniteAt[here.getX()][here.getY()] -= u.workerHarvestAmount();
		if (karboniteAt[here.getX()][here.getY()] < 0)
			karboniteAt[here.getX()][here.getY()] = 0;
	}
}
