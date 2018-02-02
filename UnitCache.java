import bc.*;

/*
 * We are given all the units we are aware of
 * Our units can change position during the turn - enemy units can't but they can be destroyed by damage we inflict
 * We keep the current state and last turns state. If a unit hasn't moved we mark it as stuck.
 */
public class UnitCache {
	private GameController	gc;
	private long			cacheRound; //The round the cache was last updated
	private VecUnit			known; //List of all units from game controller
	private Unit[][]		units; //Array (by location x and y) of known units
	
	public UnitCache(GameController g) {
		gc = g;
		known = null;
		
		int width = (int)gc.startingMap(gc.planet()).getWidth();
		int height = (int)gc.startingMap(gc.planet()).getHeight();
		
		units = new Unit[width][height];
		
		updateCache();
	}
	
	public void updateCache() {
		if (cacheRound == gc.round()) //Already done
			return;

		for (int x = 0; x < units.length; x++)
			for (int y = 0; y < units[0].length; y++)
				units[x][y] = null;

		if (known != null)
			known.delete();
		
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
