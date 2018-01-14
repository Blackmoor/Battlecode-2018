import java.util.ArrayList;

import bc.*;

/*
 * The class stores information about distinctly separate areas of a map (Mars)
 */
public class MapZone implements Comparable<MapZone> {
	public ArrayList<MapLocation>	tiles; //List of connected tiles in this area
	public ArrayList<MapLocation>	landingSites; //subset of tiles where we can land
	public int						karbonite; //Total karbonite that will land in this area
	public int						id; //Zone ID starting at 0
	
	public MapZone(int zoneId) {
		tiles = new ArrayList<MapLocation>();
		landingSites = new ArrayList<MapLocation>();
		karbonite = 0;
		id = zoneId;
	}
	
	public int compareTo(MapZone other) {
		return (other.tiles.size() + other.karbonite/10) - (tiles.size() + karbonite/10);
	}
}
