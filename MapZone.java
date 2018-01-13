import java.util.ArrayList;

import bc.*;

/*
 * The class stores information about distinctly separate areas of a map (Mars)
 */
public class MapZone implements Comparable<MapZone> {
	public ArrayList<MapLocation>	tiles; //List of conected tiles in this area
	public int						karbonite; //Total karbonite that will land in this area
	
	public MapZone(ArrayList<MapLocation> t, int k) {
		tiles = t;
		karbonite = k;
	}
	
	public int compareTo(MapZone other) {
		return (other.tiles.size() + other.karbonite/10) - (tiles.size() + karbonite/10);
	}
}
