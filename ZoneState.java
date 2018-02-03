import java.util.Arrays;
import java.util.LinkedList;

import bc.MapLocation;
import bc.Unit;
import bc.UnitType;

/*
 * Stores details on the units in a given zone on the map
 */
public class ZoneState {
    public int[] enemyUnits; //Counts of how many units enemy has indexed by unit type (ordinal)
    public int[] myLandUnits; //Counts of how many units we have indexed by unit type (ordinal)
    public LinkedList<MapLocation> unitsToBuild; //List of current blueprints that need building
    public LinkedList<Unit> rockets; //List of rockets (to Load into if on Earth, or unload from on Mars)
    public UnitType strategy;
    
    public ZoneState() {
		myLandUnits = new int[UnitType.values().length];
		enemyUnits = new int[UnitType.values().length];
		unitsToBuild = new LinkedList<MapLocation>();
		rockets = new LinkedList<Unit>();
		strategy = UnitType.Ranger;
    }
    
    public void clear() {
	    Arrays.fill(myLandUnits, 0);
    	Arrays.fill(enemyUnits, 0);
    	unitsToBuild.clear();
    	rockets.clear();
    }
    
    public int rocketsNeeded(long currentRound) {
    	int unitsToTransport = (myLandUnits[UnitType.Healer.ordinal()]) +
				myLandUnits[UnitType.Ranger.ordinal()] +
				myLandUnits[UnitType.Mage.ordinal()] +
				myLandUnits[UnitType.Knight.ordinal()];
		if (currentRound > 700)
			unitsToTransport += myLandUnits[UnitType.Worker.ordinal()];
		
		return ((unitsToTransport+7) / 8) - myLandUnits[UnitType.Rocket.ordinal()];
    }
}
