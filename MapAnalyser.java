import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;

import bc.*;

public class MapAnalyser {
	public ArrayList<MapZone>	zones;	//The distinct zones found on the map
	private PlanetMap			map; //The map being analysed
	private short[][]			zoneArray; //Indexed by x,y coords - contains the zoneId of the location
	
	public MapAnalyser(GameController gc, PlanetMap pm, MapInfo[][] info) {
		map = pm;
		
		/*
		 * We scan for zones in the map using a flood search (BFS) and mark each one with a unique id
		 */
		short currentZone = 1;
		zoneArray = new short[(int) map.getWidth()][(int) map.getHeight()];
		
		for (int x=0; x<map.getWidth(); x++) {
			for (int y=0; y<map.getHeight(); y++) {
				MapLocation here = getLocation(info, x, y);
				if (map.isPassableTerrainAt(here) > 0 && zoneArray[x][y] == 0) {
					flood(here, zoneArray, currentZone);
					currentZone++;
				}
			}
		}
		
		/*
		 * Create MapZones to store the results
		 */
		zones = new ArrayList<MapZone>();
		for (int i=0; i<currentZone-1; i++) {
			zones.add(new MapZone(i));
		}
		
		for (int x=0; x<map.getWidth(); x++) {
			for (int y=0; y<map.getHeight(); y++) {
				if (zoneArray[x][y] > 0) {
					MapLocation here = getLocation(info, x, y);
					MapZone zone = zones.get(zoneArray[x][y]-1);
					zone.tiles.add(here);
					zone.landingSites.add(here);
					zone.karbonite += map.initialKarboniteAt(here);
					if (info != null)
						info[x][y].zone = zone.id;
				}
			}
		}
		
		/*
		 * On Mars we add the asteroids info
		 */		
		if (map.getPlanet() == Planet.Mars) {
			for (int round=0; round<1000; round++) {
				if (gc.asteroidPattern().hasAsteroid(round)) {
					MapLocation where = gc.asteroidPattern().asteroid(round).getLocation();
					long karbonite = gc.asteroidPattern().asteroid(round).getKarbonite();
					int zoneID = zoneArray[where.getX()][where.getY()];
					if (zoneID > 0)
						zones.get(zoneID-1).karbonite += karbonite;
				}
			}
		}
		
		/*
		 * Finally sort the list of zones - putting the best one first
		 */
		Collections.sort(zones);
	}
	
	public int getZone(MapLocation m) {
		return zoneArray[m.getX()][m.getY()];
	}
	
	private MapLocation getLocation(MapInfo[][] m, int x, int y) {
		if (m == null)
			return new MapLocation(map.getPlanet(), x, y);
		
		return m[x][y].here;
	}
	
	private void flood(MapLocation start, short[][] zone, short zoneID) {
		LinkedList<MapLocation> openList = new LinkedList<MapLocation>();
		
		openList.add(start);
		zone[start.getX()][start.getY()] = zoneID;
		while (!openList.isEmpty()) {
			MapLocation here = openList.removeFirst();
			int x = here.getX(), y = here.getY();
				
			//Add all passable neighbours that we haven't seen before
			for (int i=-1; i<=1; i++) {
				for (int j=-1; j<=1; j++) {
					MapLocation next = new MapLocation(map.getPlanet(), x+i, y+j);
					if (map.onMap(next) && zone[x+i][y+j] == 0 && map.isPassableTerrainAt(next) > 0) {
						zone[x+i][y+j] = zoneID;
						openList.addLast(next);
					}
				}
			}
		}
	}
}
