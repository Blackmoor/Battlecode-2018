import java.util.Arrays;
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
	 private MapCache map;
	 private boolean explored; //Set to true if there are no locations that are passable and not visible
	 
	 public MapState(MapCache mc) {
		 map = mc;		 
		 width = mc.width();
		 height = mc.height();
		 explored = false;
		 danger = new int[width][height];
		 visible = new boolean[width][height];
	 }
	 
	 public void clear() {
		 exploreZone.clear();
		 for (int i=0; i<danger.length; i++) {
			 Arrays.fill(danger[i], 0);
			 Arrays.fill(visible[i], false);
		 }			 
	 }
	 
	 public void addVisibility(LinkedList<MapLocation> within) {
 		for (MapLocation m: within) {
			int x = m.getX(), y = m.getY();
			visible[x][y] = true;	
		}
	 }
	 
	 public void explore() {
		 explored = true;	 
		 for (int x=0; x<width; x++) {
			for (int y=0; y<height; y++) {  
				if (!visible[x][y] && map.passable(x, y)) { //Unseen - are we adjacent to a visible location
					explored = false;
					for (MapLocation m:map.passableNeighbours(x, y)) {
						if (visible[m.getX()][m.getY()]) {
							exploreZone.add(map.loc(x, y));
							break;
						}
					}
				}
			}
		}
	 }
	 
	 public boolean explored() {
		 return explored;
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
	 
	 public int danger(MapLocation m) {
		 return danger[m.getX()][m.getY()];
	 }
	 
	 public LinkedList<MapLocation> exploreZone() {
		 return exploreZone;
	 }
}
