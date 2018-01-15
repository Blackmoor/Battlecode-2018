// import the API.
// See xxx for the javadocs.
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import bc.*;

public class Player {
	private static GameController gc;
	private static Team myTeam; //Red or Blue
	private static Team otherTeam;
	private static Planet myPlanet; //(Earth or Mars)
    private static PlanetMap map;	//Initial map 
    private static MapAnalyser mars;
    
    private static long currentRound; //Updated each turn
    private static UnitCache units; //The list of all known units - updated each turn in the main loop   
    private static VecUnit unitsInSpace; //The list of all units on the way to mars
    private static int totalKarbonite; //How much Karbonite is available on the starting map
    private static Random randomness = new Random(1042);    
    private static HashMap<Integer, Long> launchRound = new HashMap<Integer, Long>(); //List of scheduled launches (rocket id, round)
    
    private static final int debugLevel = 0;
    
    public static void main(String[] args) {
        // Connect to the manager, starting the game
        gc = new GameController();
        myTeam = gc.team();
        if (myTeam == Team.Blue)
        	otherTeam = Team.Red;
        else
        	otherTeam = Team.Blue;

        //Cache the initial map
        myPlanet = gc.planet();
        map = gc.startingMap(myPlanet);
        units = new UnitCache(gc);
        
    	scanMap();
    	debug(1, "Total Karbonite on " + myPlanet + " is " + totalKarbonite);
    	
        if (myPlanet == Planet.Earth) {
	        //Start our research
	        gc.queueResearch(UnitType.Rocket); // Allows us to build rockets (100 turns)   
	        gc.queueResearch(UnitType.Worker); // Improves Karbonite harvesting (25 turns)
	        gc.queueResearch(UnitType.Ranger); // Increase movement rate (25 turns)
	        gc.queueResearch(UnitType.Ranger); // Increase Vision Range (100 Turns)
	        gc.queueResearch(UnitType.Mage); //Increase Damage (25 turns)
	        gc.queueResearch(UnitType.Mage); //Increase Damage (75 turns)
	        gc.queueResearch(UnitType.Mage); //Increase Damage (100 turns)
	        gc.queueResearch(UnitType.Mage); //Blink (200 Turns)	        
	        gc.queueResearch(UnitType.Ranger); // Snipe (200 Turns)
	        gc.queueResearch(UnitType.Healer); // Better Healing (25 Turns)
	        gc.queueResearch(UnitType.Healer); // Better Healing (100 Turns)
	        gc.queueResearch(UnitType.Healer); // Overdrive (200 Turns)
        }
        
        runPlanet();
    }
 
    private static void debug(int level, String s) {
    	if (level <= debugLevel)
    		System.out.println(s);
    }
    
    /***************************************************************************************
     * Utility functions
     * These are sometimes local versions of the gc routines that perform better
     ***************************************************************************************/
    private static ArrayList<MapLocation> allLocationsWithin(MapLocation centre, long radiusSq) {
    	ArrayList<MapLocation> result = new ArrayList<MapLocation>();
    	
    	int cx = centre.getX(), cy = centre.getY();
    	int width = (int) map.getWidth(), height = (int) map.getHeight();
    	result.add(centre);
    	
    	//Scan tiles in 1 quadrant (top right from centre) and add 4 tiles to list if it is in range
    	int maxOffset = (int) Math.sqrt(radiusSq);
    	for (int x=0; x<maxOffset; x++)
    		for (int y=1; y<maxOffset; y++)
    			if (x*x+y*y <= radiusSq) {
    				if (cx+x < width && cy+y < height)
    					result.add(new MapLocation(myPlanet, cx+x, cy+y));
    				if (cx-y >= 0 && cy+x < height)
    					result.add(new MapLocation(myPlanet, cx-y, cy+x));
    				if (cx-x >= 0 && cy-y >= 0)
    					result.add(new MapLocation(myPlanet, cx-x, cy-y));
    				if (cx+y < width && cy-x >= 0)
    					result.add(new MapLocation(myPlanet, cx+y, cy-x));
    			}

    	return result;
    }
    
    private static ArrayList<MapLocation> allNeighboursOf(MapLocation centre) {
    	ArrayList<MapLocation> result = new ArrayList<MapLocation>();
    	int cx = centre.getX(), cy = centre.getY();
    	int width = (int) map.getWidth(), height = (int) map.getHeight();
    	
    	if (cx > 0) {
    		result.add(new MapLocation(myPlanet, cx-1, cy));
    		if (cy > 0)
    			result.add(new MapLocation(myPlanet, cx-1, cy-1));
    		if (cy+1 < height)
    			result.add(new MapLocation(myPlanet, cx-1, cy+1));
    	}
    	if (cy > 0)
			result.add(new MapLocation(myPlanet, cx, cy-1));
		if (cy+1 < height)
			result.add(new MapLocation(myPlanet, cx, cy+1));
		if (cx+1 < width) {
			result.add(new MapLocation(myPlanet, cx+1, cy));
    		if (cy > 0)
    			result.add(new MapLocation(myPlanet, cx+1, cy-1));
    		if (cy+1 < height)
    			result.add(new MapLocation(myPlanet, cx+1, cy+1));
		}
		return result;
    }
    
    /*
     * Returns true if the unit is our and a completed factory or rocket
     */
    private static boolean isOurStructure(Unit u) {
    	if (u == null || u.team() != myTeam)
    		return false;
    	
    	return ((u.unitType() == UnitType.Factory || u.unitType() == UnitType.Rocket)
    			&& u.structureIsBuilt() > 0);
    }
    
    /*
     * Returns an array containing all the passable neighbours of a map location
     * Passable means on the map and not water
     */
    private static List<MapLocation> allPassableNeighbours(MapLocation l) {
    	List<MapLocation> result = new ArrayList<MapLocation>();
    	for (MapLocation test:allNeighboursOf(l)) {
    		if (passable[test.getX()][test.getY()])
    			result.add(test);
		}
    	
    	return result;
    }
    
    /*
     * Returns an array containing all the open neighbours of a map location
     * Open means on the map and not water and doesn't contain a unit
     */
    private static List<MapLocation> allOpenNeighbours(MapLocation l) {
    	List<MapLocation> result = new ArrayList<MapLocation>();
    	
    	for (MapLocation test:allNeighboursOf(l)) {
    		if (passable[test.getX()][test.getY()] && units.unitAt(test) == null)
				result.add(test);
		}
    	
    	return result;
    }
    
    /*
     * Returns an array containing all the neighbours of a map location we can move to
     * Open means on the map and not water and doesn't contain a blocking structure
     */
    private static List<MapLocation> allMoveNeighbours(MapLocation l) {
    	List<MapLocation> result = new ArrayList<MapLocation>();
    	for (MapLocation test:allNeighboursOf(l)) {
    		int x = test.getX(), y = test.getY();
    		Unit u = units.unitAt(test);
			if (passable[x][y] && (u == null || isOurStructure(u)))
				result.add(test);
		}
    	
    	return result;
    }
    
    
    /**************************************************************************************
     * Gravity Map functions
	 * All movement is based on gravity wells generated by points of interest
     * The gravity well maps are stored in arrays that map onto the Planet map
     * High scores are more interesting - units move to an adjacent tile with a higher score if possible
     * 
     * We create these maps each turn that a unit needs to move and only as needed
     **************************************************************************************/
    
    private static ArrayList<MapLocation> karboniteLocation = new ArrayList<MapLocation>(); //Initialised with starting values and updated as we sense tiles with new values
    private static boolean[][] passable; //Set to true if the location is passable (i.e. not water) - fixed
    
    private static double[][] workerMap = null;
    private static double[][] mageMap = null;
    private static double[][] rangerMap = null;
    private static double[][] healerMap = null;
    private static double[][] knightMap = null;
    
    private static long workerMapLastUpdated = -1;
    private static long mageMapLastUpdated = -1;
    private static long rangerMapLastUpdated = -1;
    private static long healerMapLastUpdated = -1;
    private static long knightMapLastUpdated = -1;
    
    /*
     * Ripple out from the given edge (set of points) until a given number of our units have been found scoring each tile as we go - the nearer the higher the score
     * For each location we mark it as open (i.e. on the list to process), closed (processed) or unseen
     * 
     * Since this routine is called more than any other - efficiency is key.
     */
    public static void ripple(double[][] gravityMap, List<MapLocation> edge, double points, UnitType match, int max) {
    	int UNSEEN = 0, OPEN = 1, CLOSED = 2;
    	int[][] status = new int[(int)map.getWidth()][(int)map.getHeight()];
    	int distance = 0; //How far from the source are we
    	int matchCount = 0; //How many units of the right type have we seen
    	
    	/*
    	 * Mark all given locations as open
    	 * Remove duplicates
    	 */
    	for (Iterator<MapLocation> iterator = edge.iterator(); iterator.hasNext();) {
    	    MapLocation m = iterator.next();
    	    if (status[m.getX()][m.getY()] == UNSEEN)
    	    	status[m.getX()][m.getY()] = OPEN;
    	    else
    			iterator.remove();
    	}
    	
    	debug(3, "ripple: starting points " + edge.size() + " value " + points + " stop when " + max + " " + match);
    	/*
    	 * This is a modified Breadth First Search
    	 * Since we want to know the distance from the source we maintain a current open list (edge)
    	 * and build up the next open list (nextEdge). When edge is processed (empty) we increment the distance and switch to the nextEdge
    	 */
    	while (!edge.isEmpty()) {
    		distance++;
    		List<MapLocation> nextEdge = new LinkedList<MapLocation>();
    		
        	for (MapLocation me: edge) {
        		if (status[me.getX()][me.getY()] != CLOSED) {
        			Unit unit = units.unitAt(me);
    				if (unit != null && unit.team() == myTeam &&
    						(match == null || match == unit.unitType())) {
						matchCount++;
						if (matchCount >= max) { //We have reached the cutoff point
							debug(3, "Ripple match count met: complete at distance " + distance);
							return;
						}
					}
        			
	        		status[me.getX()][me.getY()] = CLOSED;
	        		debug(4, "Ripple SEEN " + me);

	    			//Score this tile
					gravityMap[me.getX()][me.getY()] += points/(distance*distance);
		       		
	    			//We add adjacent tiles to the next search if they are traversable
					for (MapLocation t:allPassableNeighbours(me)) {
		    			if (status[t.getX()][t.getY()] == UNSEEN) {
			    			nextEdge.add(t);
			    			status[t.getX()][t.getY()] = OPEN;
			    			debug(4, "ripple Added " + t);
		    			}
		    		}
    			}
    		}
    		debug(4, "Ripple distance " + distance + " edge size = " + nextEdge.size());
    		
    		edge = nextEdge;
    	}
    	
    	debug(3, "Ripple queue empty: complete at distance " + distance);
    }
    
    public static void ripple(double[][] gravityMap, MapLocation t, double points, UnitType match, int max) {
    	List<MapLocation> edge = new LinkedList<MapLocation>();
    	edge.add(t);

    	ripple(gravityMap, edge, points, match, max);
    }
    
    public static void ripple(double[][] gravityMap, int x, int y, double points, UnitType match, int max) {
    	List<MapLocation> edge = new LinkedList<MapLocation>();
    	edge.add(new MapLocation(myPlanet, x, y));

    	ripple(gravityMap, edge, points, match, max);
    }
    
    private static int maxUnitsOnRocket(UnitType t) {
    	switch (t) {
	    	case Mage:
	    		return 4;
	    	case Ranger:
	    		return 2;
	    	default:
	    		return 1;
    	}
    }
    
    /*
     * addRockets
     * 
     * Adds in gravity for each active rocket on earth looking for passengers
     * Currently we limit a rocket to
     *  1 Worker
     *  2 Rangers
     *  4 Mages
     *  1 Healer
     */
    private static void addRockets(double[][] map, UnitType match) {
    	//Add Rockets that are ready to board and have space
		if (myPlanet == Planet.Earth) {
    		for (Unit r: rockets) {
    			VecUnitID loaded = r.structureGarrison();
    			int count = 0;
    			for (long i=0; i<loaded.size(); i++) {
    				Unit u = gc.unit(loaded.get(i));
    				if (u.unitType() == match)
    					count++;
    			}
    			
    			int allowed = maxUnitsOnRocket(match) - count;
    			debug(3, "Rocket has room for " + allowed + " " + match + "'s");
    			if (allowed > 0)
    				ripple(map, r.location().mapLocation(), 1000, match, allowed);
    		}
		}
    }
    
    /*
     * addDangerZones
     * 
     * subtracts from all tiles on the map within the attack range of enemy units we can see
     */
    private static void addDangerZones(double[][] gravityMap) {
    	//We have cached the threatened tile so just loop through the locations array
    	for (int x=0; x<map.getWidth(); x++)
    		for (int y=0; y<map.getHeight(); y++)
    			if (danger[x][y])
    				gravityMap[x][y] -= 150;
    }
    
    private static void createWhiteNoiseMap(double[][] noise) {
    	for (int x=0; x<map.getWidth(); x++) {
    		for (int y=0; y<map.getHeight(); y++) {
    			noise[x][y] = randomness.nextDouble() / 1000.0;
    		}
    	}
    }
    
    /*
     * Rangers are combat units
     * They move towards enemy units, our workers (to protect them) and rockets.
     * 
	 * - Enemy unit - Strength = 5*Cost, Max = 5
	 * - Worker ally - Strength = Cost, Max = 1
	 * - Rocket - Strength = 1000, Max = 1
     */
    private static void updateRangerMap() {
    	if (rangerMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	rangerMapLastUpdated = currentRound;
    	createWhiteNoiseMap(rangerMap);
    	List<MapLocation> targets = new ArrayList<MapLocation>();
    	int rangerCount = myLandUnits[UnitType.Ranger.ordinal()];
    	
    	//Add enemies
    	for (Unit u:enemies) {
    		//We want to be at our attack distance from each enemy
    		MapLocation enemyLoc = u.location().mapLocation();
    		for (MapLocation m:allLocationsWithin(enemyLoc, 50)) {
    			//We only want the tiles that are a reasonable distance from the enemy
    			if (m.distanceSquaredTo(enemyLoc) > 10)
    				targets.add(m);
    			else {
    				int x = m.getX(), y = m.getY();
    				rangerMap[x][y] -= 100;
    			}
    		}
    	}

    	ripple(rangerMap, targets, 100, UnitType.Ranger, rangerCount);
    	
    	addDangerZones(rangerMap);
    	addRockets(rangerMap, UnitType.Ranger);
    }
    
    private static void updateMageMap() {
    	if (mageMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	mageMapLastUpdated = currentRound;
    	createWhiteNoiseMap(mageMap);
    	List<MapLocation> targets = new ArrayList<MapLocation>();
    	int mageCount = myLandUnits[UnitType.Mage.ordinal()];
    	
    	//Add enemies
    	for (Unit u:enemies) {
    		//We want to be at our attack distance from each enemy
    		MapLocation enemyLoc = u.location().mapLocation();
    		targets.addAll(allLocationsWithin(enemyLoc, 30));
    	}    	
    	ripple(mageMap, targets, 100, UnitType.Mage, mageCount);
    	
    	addDangerZones(mageMap);
    	addRockets(mageMap, UnitType.Mage);
    }
    
    /*
     * Healers need to move towards damaged allies (not structures)
     * Like all other units they will board waiting rockets
     */
    private static void updateHealerMap() {
    	if (healerMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	healerMapLastUpdated = currentRound;
    	createWhiteNoiseMap(healerMap);
    	List<MapLocation> targets = new ArrayList<MapLocation>();
    	int healerCount = myLandUnits[UnitType.Healer.ordinal()];
    	
    	//Add damaged units
    	for (Unit u:unitsToHeal)
    		targets.add(u.location().mapLocation());
    	ripple(healerMap, targets, 100, UnitType.Healer, healerCount);
    	
    	addDangerZones(healerMap);
		addRockets(healerMap, UnitType.Healer);
    }
    
    private static void updateKnightMap() {
    	if (knightMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	knightMapLastUpdated = currentRound;
    	createWhiteNoiseMap(knightMap);
    	List<MapLocation> targets = new ArrayList<MapLocation>();
    	int knightCount = myLandUnits[UnitType.Knight.ordinal()];
    	
    	//Add enemies
    	for (Unit u:enemies)
    		targets.add(u.location().mapLocation());  	
    	ripple(knightMap, targets, 100, UnitType.Knight, knightCount);
    	
    	//Note we don't add the danger zones for knights as they need to ignore it to get in close
    	
    	addRockets(knightMap, UnitType.Knight);
    }
    
    /*
     * Workers are the busiest!
     * They respond to Karbonite, blueprints and damaged structures
     * Like all units they also board rockets when built
     */
    private static void updateWorkerMap() {
    	if (workerMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	workerMapLastUpdated = currentRound;
    	createWhiteNoiseMap(workerMap);
		List<MapLocation> targets = new ArrayList<MapLocation>();
		int workerCount = myLandUnits[UnitType.Worker.ordinal()];
		
		//Add Karbonite deposits
		ripple(workerMap, karboniteLocation, 10, UnitType.Worker, workerCount);
		
		//Add blueprints and damaged buildings
		targets.clear();
		for (Unit b: unitsToBuild) {
			MapLocation m = b.location().mapLocation();
			int x = m.getX(), y = m.getY();
			targets.add(b.location().mapLocation());
			workerMap[x][y] -= 200;
		}
		for (Unit b: unitsToRepair) {
			MapLocation m = b.location().mapLocation();
			int x = m.getX(), y = m.getY();
			targets.add(b.location().mapLocation());
			workerMap[x][y] -= 200;
		}
		ripple(workerMap, targets, 200, UnitType.Worker, Math.min(workerCount, 8));
		
		addDangerZones(workerMap);
		addRockets(workerMap, UnitType.Worker);
    }
    
    private static double[][] getGravityMap(UnitType type) {
    	switch (type) {
	    	case Worker:
	    		updateWorkerMap();
	    		return workerMap;
	    	case Ranger:
	    		updateRangerMap();
	    		return rangerMap;
	    	case Mage:
	    		updateMageMap();
	    		return mageMap;
	    	case Healer:
	    		updateHealerMap();
	    		return healerMap;
	    	case Knight:
	    		updateKnightMap();
	    		return knightMap;
	    	default:
	    		return null;
    	}
    }
    
    /*
     * Scan the map data and record the locations of karbonite, the number of passable neighbours each tile has
     * and return the total karbonite on the map
     * 
     * This is called once on the first turn
     */
    private static void scanMap() {
    	int w = (int) map.getWidth(), h = (int) map.getHeight();
    	totalKarbonite = 0;
    	passable = new boolean[w][h];
    	rangerMap = new double[w][h];
    	mageMap = new double[w][h];
    	healerMap = new double[w][h];
    	knightMap = new double[w][h];
    	workerMap = new double[w][h];
    	
    	for (int x = 0; x<map.getWidth(); x++) {
    		for (int y=0; y<map.getHeight(); y++) {
    			MapLocation here = new MapLocation(myPlanet, x, y);
    			if (map.initialKarboniteAt(here) > 0) {
    				karboniteLocation.add(here);
    				totalKarbonite += map.initialKarboniteAt(here);
    			}
    			passable[x][y] = (map.onMap(here) && map.isPassableTerrainAt(here) > 0);
    		}
    	}
    	
    	mars = new MapAnalyser(gc);
    }   
    
    /*
     * Given a gravity map we find the highest scoring tile adjacent to us
     * This could be one of our static buildings or an empty tile
     */
    private static Direction bestMove(Unit t, double[][] gravityMap) {
    	Direction best = null;
    	
    	if (!t.location().isOnMap())
    		return null;
    	
    	MapLocation myLoc = t.location().mapLocation();
    	double bestScore = gravityMap[myLoc.getX()][myLoc.getY()];
    	debug(4, "bestMove from " + myLoc + " current score " + bestScore);
    	List<MapLocation> options = allMoveNeighbours(myLoc);
    	for (MapLocation test: options) {
    		Direction d = myLoc.directionTo(test);
    		if (gravityMap[test.getX()][test.getY()] > bestScore) {
    			bestScore = gravityMap[test.getX()][test.getY()];
    			best = d;
    		}
    	}
    	debug (4, "is " + best + " with a score of " + bestScore);
		return best;
    }
    
    /*
     * updateKarbonite
     * 
     * Use current sense data to update any changes to karbonite
     * If Earth now has no Karbonite we can skip the update as no more will appear
     */
    private static void updateKarbonite() {
    	for (Iterator<MapLocation> iterator = karboniteLocation.iterator(); iterator.hasNext();) {
    	    MapLocation m = iterator.next();
    		if (gc.canSenseLocation(m)) {
    			if (gc.karboniteAt(m) == 0)
    				iterator.remove();
    		}
    	}	
    	
    	if (myPlanet == Planet.Mars) {
    		if (gc.asteroidPattern().hasAsteroid(currentRound)) {
    			MapLocation strike = gc.asteroidPattern().asteroid(currentRound).getLocation();
    			karboniteLocation.add(strike);
    		}
    	}
    }
    
    private static int[] myLandUnits = new int[UnitType.values().length]; //Counts of how many units we have indexed by unit type (ordinal)
    private static int[] mySpaceUnits = new int[UnitType.values().length]; //Counts of how many units we have indexed by unit type (ordinal)
    private static List<Unit> unitsToBuild = new ArrayList<Unit>(); //List of current blueprints that need building
    private static List<Unit> unitsToHeal = new ArrayList<Unit>(); //List of units that need healing
    private static List<Unit> unitsToRepair = new ArrayList<Unit>(); //List of buildings that need repair
    private static List<Unit> rockets = new ArrayList<Unit>(); //List of rockets (to Load into if on Earth, or unload from on Mars)
    private static List<Unit> enemies = new ArrayList<Unit>(); //List of all enemy units in sight
    private static boolean[][] danger; //Array (x,y) of map location that are threatened by enemy units
    
    /*
     * Loop through the units we are aware of and update our cache
     * Called once each turn
     */
    private static void updateUnits() {  	
        units.updateCache(); //All the units we can see on the map (but not the ones in garrisons)
        unitsInSpace = gc.unitsInSpace(); //All the units in space
    	Arrays.fill(myLandUnits, 0);
    	Arrays.fill(mySpaceUnits, 0);
    	unitsToBuild.clear();
    	unitsToHeal.clear();
    	unitsToRepair.clear();
    	rockets.clear();
    	enemies.clear();
    	danger = new boolean[(int) map.getWidth()][(int) map.getHeight()];
    	
    	VecUnit known = units.allUnits();
    	for (int i = 0; i < known.size(); i++) {
            Unit unit = known.get(i);
            
            if (unit.location().isOnMap()) {
            	if (unit.team() == myTeam) {
            		myLandUnits[unit.unitType().ordinal()]++;
            		if (unit.unitType().equals(UnitType.Factory) || unit.unitType().equals(UnitType.Rocket)) {
            			if (unit.structureIsBuilt() == 0)
            				unitsToBuild.add(unit);
            			else if (unit.health() < unit.maxHealth())
            				unitsToRepair.add(unit);
           			
            			if (unit.structureIsBuilt() > 0 && unit.unitType().equals(UnitType.Rocket) && unit.rocketIsUsed() == 0)
            				rockets.add(unit);
            			
            			VecUnitID garrison = unit.structureGarrison();
            			for (int j=0; j<garrison.size(); j++) {
            				int id = garrison.get(j);
            				myLandUnits[gc.unit(id).unitType().ordinal()]++;
            			}
            		} else {
            			if (unit.health() < unit.maxHealth())
            				unitsToHeal.add(unit);
            		}
            	} else { //enemies
            		enemies.add(unit);
            		switch (unit.unitType()) {
	            		case Ranger:
	            		case Knight:
	            		case Mage:
	            			for (MapLocation m:allLocationsWithin(unit.location().mapLocation(), unit.attackRange())) {
	            				int x = m.getX(), y = m.getY();
	            				if (unit.unitType() != UnitType.Ranger || m.distanceSquaredTo(unit.location().mapLocation()) > 10)
	            					danger[x][y] = true;
	            			}
	            			break;
	            		default: //Other units cannot attack
	            			break;
            		}
            	}
            }
    	}
    	
    	for (int i=0; i<unitsInSpace.size(); i++) {
    		Unit unit = unitsInSpace.get(i);
    		mySpaceUnits[unit.unitType().ordinal()]++;
    	}
    	
    	if (debugLevel > 0) {
	    	String unitInfo = "";
	    	for (UnitType t: UnitType.values())
	    		if (myLandUnits[t.ordinal()] > 0)
	    			unitInfo += t + " = " + myLandUnits[t.ordinal()] + " ";
	    	if (unitInfo.length() > 0)
	    		debug(1, "Round " + currentRound + ": On " + myPlanet + ": " + unitInfo);
	    	
	    	unitInfo = "";
	    	for (UnitType t: UnitType.values())
	    		if (mySpaceUnits[t.ordinal()] > 0)
	    			unitInfo += t + " = " + mySpaceUnits[t.ordinal()] + " ";
	    	if (unitInfo.length() > 0)
	    		debug(1, "Round " + currentRound + ": In space: " + unitInfo);
	    	
	    	for (int rnd=0; rnd<200; rnd++) {
	    		VecRocketLanding landings = gc.rocketLandings().landingsOn(currentRound+rnd);
	    		for (int l=0; l<landings.size(); l++)
	    			debug(1, "Rocket Landing on @ " + landings.get(l).getDestination() + " in "+ rnd + " rounds");
	    	}
    	}
    }
    
    private static void manageWorker(Unit unit) {
    	if (!unit.unitType().equals(UnitType.Worker))
    		return;
    	
    	int id = unit.id();
    	
    	if (!unit.location().isOnMap())
    		return;
    	
		//Do we want to move to a better location
        if (gc.isMoveReady(id))
        	moveUnit(unit);
            
        if (!unit.location().isOnMap())
    		return;
        
		MapLocation loc = unit.location().mapLocation();
    	List<MapLocation> options = allOpenNeighbours(loc);
    	Direction dir = null;
    	
    	if (!options.isEmpty()) {
    		//Pick a random open and safe neighbour tile
        	int r = randomness.nextInt(options.size());
        	
        	for (int i=0; i<options.size(); i++) {
        		MapLocation test = options.get((i+r)%options.size());
        		if (!danger[test.getX()][test.getY()]) {
        			dir = loc.directionTo(options.get(0));
        			break;
        		}           			
        	}
    	}       		

    	//Check to see if we can replicate
    	if (dir != null && myLandUnits[UnitType.Worker.ordinal()] + mySpaceUnits[UnitType.Worker.ordinal()] < 16) {
        	if (gc.canReplicate(id, dir)) {
        		gc.replicate(id, dir);
        		units.updateUnit(loc.add(dir)); //TODO - check to see if we can call manageWorker on new unit
        		myLandUnits[UnitType.Worker.ordinal()]++;
        		debug(2, "worker replicating");
        	}
    	}	                    
        
        //Can we help build or repair something
        if (unit.workerHasActed() == 0) { 
        	for (MapLocation m:allNeighboursOf(loc)) {			
				Unit other = units.unitAt(m);
				if (other != null) {
					if (gc.canBuild(id, other.id())) {
						gc.build(id, other.id());
						debug(2, "worker building");
						break;
					}
					if (gc.canRepair(id, other.id())) {
						gc.repair(id, other.id());
	  					debug(2, "worker is repairing");
	  					break;
					}
				}
			}
        }

		//Can we Harvest
		if (unit.workerHasActed() == 0) {
			for (Direction d: Direction.values()) {
				if (gc.canHarvest(id, d)) {
					gc.harvest(id, d);
					debug(2, "worker harvesting");
					break;
				}
			}
		}		
		
		/*
		 * Now check to see if we want to build a factory or a rocket
		 */			
		if (dir != null && unit.workerHasActed() == 0 && myPlanet == Planet.Earth) {
			int factoriesNeeded = 3 - myLandUnits[UnitType.Factory.ordinal()];
			int unitsToTransport = (myLandUnits[UnitType.Worker.ordinal()] + 
					myLandUnits[UnitType.Ranger.ordinal()] +
					myLandUnits[UnitType.Mage.ordinal()] +
					myLandUnits[UnitType.Knight.ordinal()] +
					myLandUnits[UnitType.Healer.ordinal()]);
			int rocketsNeeded = (unitsToTransport+7) / 8;
			if (factoriesNeeded > 0 && factoriesNeeded >= rocketsNeeded &&
					gc.karbonite() >= bc.bcUnitTypeBlueprintCost(UnitType.Factory) &&
					gc.canBlueprint(id, UnitType.Factory, dir)) {
				gc.blueprint(id, UnitType.Factory, dir);
				units.updateUnit(loc.add(dir));
				debug(2, "worker blueprinting factory");
				myLandUnits[UnitType.Factory.ordinal()]++;
			} else if (rocketsNeeded > 0 && rocketsNeeded >= factoriesNeeded &&
					gc.karbonite() >= bc.bcUnitTypeBlueprintCost(UnitType.Rocket) &&
					gc.canBlueprint(id, UnitType.Rocket, dir)) {
				gc.blueprint(id, UnitType.Rocket, dir);
				units.updateUnit(loc.add(dir));
				debug(2, "worker blueprinting rocket");
				myLandUnits[UnitType.Rocket.ordinal()]++;
			}
		}
    }
    
    private static int marsZone = 0; //This is the zone we want to land in next
    
    /*
     * Pick the next valid zone on mars
     * It has to have viable landing sites
     */
    private static void nextZone() {
    	//Send next rocket to next zone with room
		boolean found = false;
		int startZone = marsZone;
		while (!found) {
	    	marsZone++;
	    	if (marsZone >= mars.zones.size())
	    		marsZone = 0;
	    	found = (marsZone == startZone || mars.zones.get(marsZone).landingSites.size() > 0);
		}
	}
		
    private static MapLocation launchDestination() {
    	if (marsZone < 0) //Mars is full!
    		return null;
    	
    	//Pick a random tile in the given zone
    	MapZone zone = mars.zones.get(marsZone);
    	int size = zone.landingSites.size();
    	if (size == 0)
    		return null;
    	
    	return zone.landingSites.get(randomness.nextInt(size));  	
    }
    
    private static long getLaunchRound(Unit u) {
    	if (u.structureIsBuilt() == 0)
    		return 749; //TODO - find constant and use it
    	
    	int id = u.id();
    	if (launchRound.containsKey(id))
    		return launchRound.get(id);
    	
    	long round = 749;
    	if (currentRound + 50 < round)
    		round = currentRound + 50;
    	
    	launchRound.put(id, round);
    	debug(3, "Rocket id " + id + " will launch on round " + round);
    	return round;
    }
    
    /*
     * Rockets leave Earth when full or we reach the launch time
     */
    private static void manageRocket(Unit unit) {
    	if (!unit.unitType().equals(UnitType.Rocket))
    		return;
    	
    	int id = unit.id();
    	if (myPlanet == Planet.Earth) {
    		//Check to see if we are have a launch time or are full
    		MapLocation dest = launchDestination();
    		if (dest != null && gc.canLaunchRocket(id, dest)) {
    			boolean full = (unit.structureGarrison().size() >= unit.structureMaxCapacity());
    			boolean timeUp = (currentRound >= getLaunchRound(unit));
    			boolean takingDamage = (unit.structureGarrison().size() > 0 && unit.health() < unit.maxHealth());
    			if (full || timeUp || takingDamage) {
    				debug(2, "Launching rocket " + id + " to " + dest + " ETA " + gc.currentDurationOfFlight() + " rnds");
    				gc.launchRocket(id, dest);
    				units.removeUnit(dest);
    				launchRound.remove(id);
    				mars.zones.get(marsZone).landingSites.remove(dest);
    				nextZone();
    			}
    		}
    	} else {
			//We don't know the unit type so use the worker movement map
    		while (unit.structureGarrison().size() > 0) {
				Direction dir = bestMove(unit, getGravityMap(UnitType.Worker));
		    	if (dir == null || !gc.canUnload(id, dir))
		    		break;
		    	gc.unload(id, dir);
		    	units.updateUnit(unit.location().mapLocation().add(dir));
		    	debug(2, "unloading from rocket");
    		}
    		
    	}
    }
    
    /*
     * Factories produce combat units and allow other units (workers) to move through them
     */
    private static void manageFactory(Unit unit) {
    	if (!unit.unitType().equals(UnitType.Factory))
    		return;
    	
    	int fid = unit.id();
    	
    	//Unload units if possible
    	while (unit.structureGarrison().size() > 0) {
	    	Direction dir = bestMove(unit, getGravityMap(UnitType.Worker));
	    	if (dir == null || !gc.canUnload(fid, dir))
	    		break;
    		gc.unload(fid, dir);
	    	units.updateUnit(unit.location().mapLocation().add(dir));
    		debug(2, "unloading from factory");
    	}
    	
    	//Produce units
    	//We want squads consisting of 2 rangers, 3 mages and a healer
    	UnitType produce = (myLandUnits[UnitType.Worker.ordinal()] < 8)?UnitType.Worker:UnitType.Ranger;
    	if (2 * myLandUnits[UnitType.Mage.ordinal()] < 3 * myLandUnits[UnitType.Ranger.ordinal()])
    		produce = UnitType.Mage;
    	else if (2 * myLandUnits[UnitType.Healer.ordinal()] < myLandUnits[UnitType.Ranger.ordinal()])
    		produce = UnitType.Healer;
    	
    	if (produce == UnitType.Ranger && myLandUnits[UnitType.Ranger.ordinal()] > 10) //Save karbonite for rockets
    		return;
    	
    	if (gc.canProduceRobot(fid, produce)) {
			gc.produceRobot(fid, produce);
		}
    }
    
    private static boolean attackWeakest(Unit unit) {
		if (!unit.location().isOnMap())
			return false;
		
    	Unit target = bestTarget(unit);
		if (target == null)
			return false;

		MapLocation where = new MapLocation(myPlanet, target.location().mapLocation().getX(), target.location().mapLocation().getY());
		gc.attack(unit.id(), target.id());
		units.updateUnit(where);
		debug(2, "Unit " + unit.id() + " " + unit.unitType() + " firing on " + target.unitType() + " @ " + where);
		return true;
    }
    
    private static void manageMage(Unit unit) {
    	int id = unit.id();
    	boolean canAttack = (unit.attackHeat() < 10);
    	
    	//Do we want to blink to a better location
    	if (canAttack && unit.isAbilityUnlocked() > 0 && unit.abilityHeat() < 10 && unit.location().isOnMap()) {
    		MapLocation here = unit.location().mapLocation();
    		//We can blink to best location in sight range
    		updateMageMap();
    		double bestScore = mageMap[here.getX()][here.getY()];
    		MapLocation bestOption = here;
    		for (MapLocation o:allLocationsWithin(here, unit.abilityRange())) {
    			if (mageMap[o.getX()][o.getY()] > bestScore && passable[o.getX()][o.getY()] &&
    					units.unitAt(o) == null) {
    				bestScore = mageMap[o.getX()][o.getY()];
    				bestOption = o;
    			}
    		}
    		if (here.distanceSquaredTo(bestOption) >= 2) {
    			units.removeUnit(here);
    			gc.blink(id, bestOption);
    			units.updateUnit(bestOption);
    			debug(2, "Mage is blinking to " + bestOption);
    		}
    	}
    	
    	if (canAttack)
    		canAttack = !attackWeakest(unit);	   	
    	
    	if (gc.isMoveReady(id))
        	moveUnit(unit);
        
    	if (canAttack)
    		attackWeakest(unit);
    }
    
    /*
     * manageRanger
     * 
     * As a combat unit we attack enemies and scout
     */
    private static void manageRanger(Unit unit) {
    	int id = unit.id();
    	boolean canAttack = (unit.attackHeat() < 10);
    	
    	//Rangers need to be mobile - we need to be able to shoot then move or to move then shoot
    	
    	if (canAttack) 
    		canAttack = !attackWeakest(unit); 	
    	
    	//Do we want to move to a better location
        if (gc.isMoveReady(id))
        	moveUnit(unit);
        	
        if (canAttack)
    		attackWeakest(unit);
    }
    
    private static void manageKnight(Unit unit) {
    	manageRanger(unit);
    }
    
    /*
     * manageHealer
     * 
     * We don't fight so we heal anyone in range
     */
    private static void manageHealer(Unit unit) {
    	if (!unit.location().isOnMap())
    		return;
    	
    	if (gc.isMoveReady(unit.id()))
        	moveUnit(unit);
    	
    	if (unit.attackHeat() < 10) {
	    	VecUnit inSight = gc.senseNearbyUnitsByTeam(unit.location().mapLocation(), unit.attackRange(), myTeam);
	    	for (int i=0; i<inSight.size(); i++) {
	    		Unit u = inSight.get(i);
	    		if (u.health() < u.maxHealth() && gc.canHeal(unit.id(), u.id())) {
					gc.heal(unit.id(), u.id());
					debug(2, "Healing " + u.unitType() + " @ " + u.location().mapLocation());
					break;
	    		}
	    	}
    	}
    }
    
    /*
     * Look for a target in range and pick the one with the most damage to fire on
     */
    private static Unit bestTarget(Unit unit) {
    	if (!unit.location().isOnMap())
    		return null;
    	
    	VecUnit inSight = gc.senseNearbyUnitsByTeam(unit.location().mapLocation(), unit.attackRange(), otherTeam);
    	//Pick the enemy with the most damage that is in range
    	int id = unit.id();
    	long mostDamage = -1;
    	Unit best = null;
    	
    	for (int i=0; i<inSight.size(); i++) {
    		Unit enemy = inSight.get(i);
    		if (gc.canAttack(id, enemy.id()) && enemy.maxHealth() - enemy.health() > mostDamage) {
    			best = enemy;
    			mostDamage = enemy.maxHealth() - enemy.health();
    		}
    	}
    	
    	return best;
    }
    
    
    private static void moveUnit(Unit unit) {
    	if (!unit.location().isOnMap())
    		return;
    	
    	Direction d = bestMove(unit, getGravityMap(unit.unitType()));
    	
    	if (d != null) {
        	MapLocation loc = unit.location().mapLocation();
        	int id = unit.id();
    		MapLocation dest = loc.add(d);
    		
    		if (gc.canMove(id, d)) {
    			units.removeUnit(loc);
    			gc.moveRobot(id, d);  			
    			units.updateUnit(dest);
    		} else { //Check to see if there is a structure of ours there
    			Unit structure = units.unitAt(dest);
    			if (structure != null) {
	    			if (gc.canLoad(structure.id(), id)) {
	    				units.removeUnit(loc);
	    				gc.load(structure.id(), id);	    				
	    				debug(2, "Loading " + unit.unitType() + " into " + structure.unitType());
	    			}
    			}
    		}
    	}	
    }
    
    private static void runPlanet() {
    	
        while (true) {
        	try {
        		currentRound = gc.round();
	            updateUnits(); //All units we can see - allies and enemies
	            updateKarbonite(); //Current known karbonite values
	            
	            VecUnit known = units.allUnits();
	            
	            for (int i = 0; i < known.size() && gc.getTimeLeftMs() > 500; i++) {
	                Unit unit = known.get(i);
	                
	                if (unit.team() == myTeam) {  
	                	switch(unit.unitType()) {
		                	case Worker:
		                		manageWorker(unit);
		                		break;
		                	case Knight:
		                		manageKnight(unit);
		                		break;
		                	case Ranger:
		                		manageRanger(unit);
		                		break;
		                	case Mage:
		                		manageMage(unit);
		                		break;
		                	case Factory:
		                		manageFactory(unit);
		                		break;
		                	case Healer:
		                		manageHealer(unit);
		                		break;
		                	case Rocket:
		                		if (myPlanet == Planet.Mars)
		                			manageRocket(unit);
		                	default: //Rockets are handled at the end of the round
		                		break;
	                	}		                
	                }
	            }

	            /*
	             * Handle Earth rockets at the end of the round as we want to give everyone a chance to move in first
	             */
	            if (myPlanet == Planet.Earth) {
		            for (Unit r:rockets) {
		            	if (gc.getTimeLeftMs() < 500)
		            		break;
		            	manageRocket(r);
		            }
	            }
	            
        		debug(0, "Time left at end of round " + currentRound + " = " + gc.getTimeLeftMs());
	            gc.nextTurn();
        	} catch (Exception e) {
        		//Ignore
        		debug(0, "Caught exception " + e);
        		e.printStackTrace();
        	}
        }
    }
}