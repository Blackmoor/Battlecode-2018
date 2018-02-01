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
	private int maxWorkers; //How many workers we need to mine this planet
	
	public Karbonite(GameController g, MapCache mc) {
		gc = g;
		Planet	myPlanet = gc.planet();
		PlanetMap map = gc.startingMap(myPlanet);
		int w = (int) map.getWidth(), h = (int) map.getHeight();
		int turnsToMine = 0;
		
		karboniteAt = new long[w][h];
		karboniteLocations = new LinkedList<MapLocation>();
		for (int x = 0; x<map.getWidth(); x++) {
			for (int y=0; y<map.getHeight(); y++) {
				MapLocation m = mc.loc(x, y);
				karboniteAt[x][y] = map.initialKarboniteAt(m);
				if (karboniteAt[x][y] > 0) {
					karboniteLocations.add(m);
					turnsToMine += (karboniteAt[x][y] + 2) / 3;
				}   
			}
		}
    	
    	int minWorkers = (turnsToMine == 0?4:8);
    	maxWorkers = Math.max(minWorkers, turnsToMine / 100);
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
	
	public int maxWorkers() {
		return maxWorkers;
	}
	
	/*
	 * Update all currently known karbonite values
	 */
	public void update(boolean visible[][]) {
		for (Iterator<MapLocation> iterator = karboniteLocations.iterator(); iterator.hasNext();) {
    	    MapLocation m = iterator.next();   	    
    		if (visible[m.getX()][m.getY()]) {
    			long k = gc.karboniteAt(m);
    			if (k == 0)
    				iterator.remove();
    			karboniteAt[m.getX()][m.getY()] = k;			
    		}
    	}	
    	
    	if (gc.planet() == Planet.Mars) {
    		long currentRound = gc.round();
    		if (gc.asteroidPattern().hasAsteroid(currentRound)) {
    			MapLocation strike = gc.asteroidPattern().asteroid(currentRound).getLocation();
    			karboniteLocations.add(strike);
    			karboniteAt[strike.getX()][strike.getY()] += gc.asteroidPattern().asteroid(currentRound).getKarbonite();
    		}
    	}
	}
	
	public void harvest(MapLocation here, Unit u) {
		karboniteAt[here.getX()][here.getY()] -= u.workerHarvestAmount();
		if (karboniteAt[here.getX()][here.getY()] < 0)
			karboniteAt[here.getX()][here.getY()] = 0;
	}
}
