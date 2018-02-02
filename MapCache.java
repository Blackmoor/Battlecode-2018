import java.util.LinkedList;

import bc.*;

/*
 * Stores the static parts of a planet map
 */
public class MapCache {
	private MapInfo[][]	map;
	private int w; //map width
	private int h; //map height
	
	public MapCache(PlanetMap pm) {
		w = (int) pm.getWidth();
		h = (int) pm.getHeight();
		
		map = new MapInfo[w][h];
		Planet planet = pm.getPlanet();
		
		/*
		 * Create the cache of locations
		 */
        for (int x = 0; x<w; x++) {
    		for (int y=0; y<h; y++) {
    			MapLocation m = new MapLocation(planet, x, y);
    			map[x][y] = new MapInfo(m, (pm.isPassableTerrainAt(m) > 0)); 
    		}
		}
        
        /*
         * Now cache all the neighbours of each location and a subset of that (passable locations) for quick access later
         */
    	for (int x = 0; x<w; x++) {
    		for (int y=0; y<h; y++) {
    			MapLocation here = map[x][y].here;
    			map[x][y].neighbours = allNeighboursOf(here);
    			map[x][y].passableNeighbours = allPassableNeighbours(here);   						
    		}
    	}        
	}

	public int width() {
		return w;
	}
	
	public int height() {
		return h;
	}
	
	public MapLocation loc(int x, int y) {
		return map[x][y].here;
	}
	
	public LinkedList<MapLocation> neighbours(int x, int y) {
		return map[x][y].neighbours;
	}
	
	public LinkedList<MapLocation> neighbours(MapLocation m) {
		return map[m.getX()][m.getY()].neighbours;
	}
	
	public LinkedList<MapLocation> passableNeighbours(int x, int y) {
		return map[x][y].passableNeighbours;
	}
	
	public LinkedList<MapLocation> passableNeighbours(MapLocation m) {
		return map[m.getX()][m.getY()].passableNeighbours;
	}
	
	public boolean passable(int x, int y) {
		return map[x][y].passable;
	}
	
	public boolean passable(MapLocation m) {
		return map[m.getX()][m.getY()].passable;
	}
	
	public int zone(int x, int y) {
		return map[x][y].zone;
	}
	
	public int zone(MapLocation m) {
		return map[m.getX()][m.getY()].zone;
	}
	
	public void setZone(int x, int y, int z) {
		map[x][y].zone = z;
	}
	
	public void setZone(MapLocation m, int z) {
		map[m.getX()][m.getY()].zone = z;
	}
	
  	private LinkedList<MapLocation> allNeighboursOf(MapLocation centre) {
    	LinkedList<MapLocation> result = new LinkedList<MapLocation>();
    	int cx = centre.getX(), cy = centre.getY();

    	if (cx > 0) {
    		result.add(map[cx-1][cy].here);
    		if (cy > 0)
    			result.add(map[cx-1][cy-1].here);
    		if (cy+1 < h)
    			result.add(map[cx-1][cy+1].here);
    	}
    	if (cy > 0)
			result.add(map[cx][cy-1].here);
		if (cy+1 < h)
			result.add(map[cx][cy+1].here);
		
		if (cx+1 < w) {
			result.add(map[cx+1][cy].here);
    		if (cy > 0)
    			result.add(map[cx+1][cy-1].here);
    		if (cy+1 < h)
    			result.add(map[cx+1][cy+1].here);
		}
		return result;
    }
    
    /*
     * Returns an array containing all the passable neighbours of a map location
     * Passable means on the map and not water
     * 
     * Only called by MapInfo to create and cache the results for each location on the map
     */
    private LinkedList<MapLocation> allPassableNeighbours(MapLocation l) {
    	LinkedList<MapLocation> result = new LinkedList<MapLocation>();
    	for (MapLocation test:map[l.getX()][l.getY()].neighbours) {
    		if (map[test.getX()][test.getY()].passable)
    			result.add(test);
		}
    	
    	return result;
    }

    public LinkedList<MapLocation> allLocationsWithin(MapLocation centre, long min, long max) {
    	LinkedList<MapLocation> result = new LinkedList<MapLocation>();
    	int cx = centre.getX(), cy = centre.getY();
    	
    	if (max == 100 && map[cx][cy].within100 != null)
    		return map[cx][cy].within100;
    	if (max == 70 && map[cx][cy].within70 != null)
    		return map[cx][cy].within70;
    	if (max == 50) {
    		if (min == 10 && map[cx][cy].within50_10 != null)
    			return map[cx][cy].within50_10;
    		if (map[cx][cy].within50 != null)
    			return map[cx][cy].within50;
    	}
    	if (max == 30) {
    		if (min == 8 && map[cx][cy].within30_8 != null)
    			return map[cx][cy].within30_8;
    		if (map[cx][cy].within30 != null)
    			return map[cx][cy].within30;
    	}   		
    	if (max == 10 && map[cx][cy].within10 != null) {
    		return map[cx][cy].within10;
    	}
    	
    	if (min < 0)
    		result.add(centre);
    	
    	//The tiles in the resultant circle will be 4 way symmetric along the diagonals and vertices
    	//and 8 way symmetric in other areas

    	//First the horizontal/vertical
    	for (int x=1; x*x<=max; x++) {
    		if (x*x <= min)
    			continue;
    		if (cx+x < w)
				result.add(map[cx+x][cy].here);
			if (cy+x < h)
				result.add(map[cx][cy+x].here);
			if (cx-x >= 0)
				result.add(map[cx-x][cy].here);
			if (cy-x >= 0)
				result.add(map[cx][cy-x].here);
    	}
    	
    	//Now the diagonals
    	for (int x=1; 2*x*x<=max; x++) {
    		if (2*x*x <= min)
    			continue;
    		if (cx+x < w) {
    			if (cy+x < h)
    				result.add(map[cx+x][cy+x].here);
    			if (cy-x >= 0)
    				result.add(map[cx+x][cy-x].here);
    		}
			if (cx-x >= 0) {
				if (cy+x < h)
					result.add(map[cx-x][cy+x].here);
				if (cy-x >= 0)
					result.add(map[cx-x][cy-x].here);
			}
    	}
    	
    	//Finally the 8 way symmetry
    	for (int x=2; x*x+1<=max; x++) {
    		if (x*x+1 <= min)
    			continue;
    		for (int y=1; y<x && x*x+y*y<=max; y++) {
    			if (x*x+y*x<=min)
    				continue;
				if (cx+x < w) {
					if (cy+y < h)
						result.add(map[cx+x][cy+y].here);
					if (cy-y >= 0)
						result.add(map[cx+x][cy-y].here);
				}
				if (cx-y >= 0) {
					if (cy+x < h)
						result.add(map[cx-y][cy+x].here);
					if (cy-x >= 0)
						result.add(map[cx-y][cy-x].here);
				}
				if (cx-x >= 0) {
					if (cy-y >= 0)
						result.add(map[cx-x][cy-y].here);
					if (cy+y < h)
						result.add(map[cx-x][cy+y].here);
				}
				if (cx+y < w) {
					if (cy-x >= 0)
						result.add(map[cx+y][cy-x].here);				
					if (cy+x < h)
						result.add(map[cx+y][cy+x].here);
				}
    		}
    	}
    	
    	if (max == 100)
    		map[cx][cy].within100 = result;
    	if (max == 70)
    		map[cx][cy].within70 = result;
    	if (max == 50) {
    		if (min == 10)
    			map[cx][cy].within50_10 = result;
    		else
    			map[cx][cy].within50 = result;
    	}
    	if (max == 30) {
    		if (min == 8)
    			map[cx][cy].within30_8 = result;
    		else
    			map[cx][cy].within30 = result;
    	}
    	if (max == 10)
    		map[cx][cy].within10 = result;
    	
    	return result;
    }
    
    private class MapInfo {
    	public MapLocation 				here;
    	public boolean					passable;
    	public int						zone; //The zone number we are part of
    	
    	public LinkedList<MapLocation>	neighbours;
    	public LinkedList<MapLocation>	passableNeighbours;
    	public LinkedList<MapLocation>	within100;
    	public LinkedList<MapLocation>	within70;
    	public LinkedList<MapLocation>	within50;
    	public LinkedList<MapLocation>	within50_10;
    	public LinkedList<MapLocation>	within30;
    	public LinkedList<MapLocation>	within30_8;
    	public LinkedList<MapLocation>	within10;
    	
    	public MapInfo(MapLocation mapLocation, boolean p) {
    		here = mapLocation;
    		passable = p;
    		neighbours = null;
    		passableNeighbours = null;
    		zone = 0;
    	}
    }
}
