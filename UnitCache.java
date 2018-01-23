import java.util.ArrayList;

import bc.*;

/*
 * We are given all our units we are aware of
 * Our units can change position in the turn - enemy units can't but they can be destroyed by damage we inflict
 * If we move then we can potentially see more enemy units but our cache will not reflect that until next turn
 */
public class UnitCache {
	private GameController	gc;
	private long			cacheRound; //The round the cache was last updated
	private VecUnit			known; //List of all units from game controller
	private Unit[][]		units; //Array (by location x and y) of known units	
	
	public UnitCache(GameController g) {
		gc = g;
		updateCache();
	}
	
	public void updateCache() {
		if (cacheRound == gc.round()) //Already done
			return;
		
		int width = (int)gc.startingMap(gc.planet()).getWidth();
		int height = (int)gc.startingMap(gc.planet()).getHeight();
		
		units = new Unit[width][height];
		known = gc.units();
		for (int i=0; i<known.size(); i++) {
			Unit u = known.get(i);
			if (u.location().isOnMap()) {
				MapLocation m = u.location().mapLocation();
				units[m.getX()][m.getY()] = u;
			}
		}
		cacheRound = gc.round();
	}
	
	public VecUnit allUnits() {
		return known;
	}
	
	public Unit unitAt(MapLocation here) {
		return units[here.getX()][here.getY()];
	}
	
	public void removeUnit(MapLocation here) {
		units[here.getX()][here.getY()] = null;
	}
	
	public Unit updateUnit(MapLocation here) {
		if (gc.hasUnitAtLocation(here))
			units[here.getX()][here.getY()] = gc.senseUnitAtLocation(here);
		else
			units[here.getX()][here.getY()] = null;
		
		return units[here.getX()][here.getY()];
	}
	
	public Unit updateUnit(int id) {
		try {
			Unit u = gc.unit(id);
			if (u != null && u.location().isOnMap()) {
				MapLocation here = u.location().mapLocation();
				units[here.getX()][here.getY()] = u;
			}
		
			return u;
		} catch (Exception e) { //Unit no longer exists - possibly killed by damage
			return null;
		}
	}
}
