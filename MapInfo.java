import java.util.LinkedList;

import bc.MapLocation;

public class MapInfo {
		public LinkedList<MapLocation>	neighbours;
    	public LinkedList<MapLocation>	passableNeighbours;
    	public LinkedList<MapLocation>	within100;
    	public LinkedList<MapLocation>	within70;
    	public LinkedList<MapLocation>	within50;
    	public LinkedList<MapLocation>	within50_10;
    	public LinkedList<MapLocation>	within30;
    	public LinkedList<MapLocation>	within30_8;
    	public MapLocation 				here;
    	public boolean					passable;
    	
    	public MapInfo(MapLocation mapLocation, boolean p) {
    		here = mapLocation;
    		passable = p;
    		neighbours = null;
    		passableNeighbours = null;
    	}
    }
