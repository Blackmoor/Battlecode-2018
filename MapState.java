import java.util.LinkedList;

import bc.*;

/*
 * MapState holds the current state of the map, the parts that change each turn such as
 * - Whether a location is visible
 * - How dangerous a location is to occupy
 * - The list of locations we want to explore next (i.e. next to a visible location but unseen)
 */
public class MapState {
	 private int[][] danger; //Array (x,y) of map locations and how much damage a unit would take there
	 private boolean[][] visible; //Array (x,y) of map locations: true we can see (sense) it
	 private LinkedList<MapLocation> exploreZone = new LinkedList<MapLocation>(); //All locs that are passable and not visible but next to a visible location
	 private int width;
	 private int height;
	 private MapCache info;
	 
	 public MapState(GameController gc, MapCache mc) {
		 Planet myPlanet = gc.planet();
		 PlanetMap map = gc.startingMap(myPlanet);
		 
		 width = (int)map.getWidth();
		 height = (int)map.getHeight();
		 info = mc;
	 }
	 
	 public void clear() {
		 exploreZone.clear();
		 danger = new int[width][height];
		 visible = new boolean[width][height];
	 }
	 
	 public void addVisibility(LinkedList<MapLocation> within) {
 		for (MapLocation m: within) {
			int x = m.getX(), y = m.getY();
			visible[x][y] = true;	
		}
	 }
	 
	 public void explore() {
		 for (int x=0; x<width; x++) {
			for (int y=0; y<height; y++) {  
				if (!visible[x][y] && info.passable(x, y)) { //Unseen - are we adjacent to a visible location
					for (MapLocation m:info.passableNeighbours(x, y)) {
						if (visible[m.getX()][m.getY()]) {
							exploreZone.add(info.loc(x, y));
							break;
						}
					}
				}
			}
		}
	 }
	 
	 public void addDanger(int x, int y, int d) {
		 danger[x][y] += d;
	 }
	 
	 public boolean visible(int x, int y) {
		 return visible[x][y];
	 }
	 
	 public int danger(int x, int y) {
		 return danger[x][y];
	 }
	 
	 public LinkedList<MapLocation> exploreZone() {
		 return exploreZone;
	 }
}
