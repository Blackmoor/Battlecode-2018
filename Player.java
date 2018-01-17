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
    private static int maxWorkers = 16; //How many workers on Earth we need to mine the karbonite
    private static Random randomness = new Random(74921);    
    
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
    	
        if (myPlanet == Planet.Earth) {
	        //Start our research 
	        gc.queueResearch(UnitType.Worker); // Improves Karbonite harvesting (25 turns)
	        gc.queueResearch(UnitType.Ranger); // Increase movement rate (25 turns)
	        gc.queueResearch(UnitType.Rocket); // Allows us to build rockets (100 turns)  
	        gc.queueResearch(UnitType.Ranger); // Increase Vision Range (100 Turns)
	        gc.queueResearch(UnitType.Ranger); // Snipe (200 Turns)	        
	        gc.queueResearch(UnitType.Mage); //Increase Damage (25 turns)
	        gc.queueResearch(UnitType.Mage); //Increase Damage (75 turns)
	        gc.queueResearch(UnitType.Mage); //Increase Damage (100 turns)
	        //gc.queueResearch(UnitType.Mage); //Blink (200 Turns)	        	        
	        gc.queueResearch(UnitType.Healer); // Better Healing (25 Turns)
	        gc.queueResearch(UnitType.Healer); // Better Healing (100 Turns)
	        gc.queueResearch(UnitType.Healer); // Overcharge (200 Turns)       
        }
        
        runPlanet();
    }
    
    private static void runPlanet() {
    	
        while (true) {
        	try {
        		long now = System.currentTimeMillis();
        		currentRound = gc.round();
        		debug(0, "Time left at start of round " + currentRound + " = " + gc.getTimeLeftMs());
       
        		if (gc.getTimeLeftMs() > 500) {	        		
		            updateUnits(); //All units we can see - allies and enemies
		            updateKarbonite(); //Current known karbonite values
		            
		            VecUnit known = units.allUnits();
		            
		            for (int i = 0; i < known.size(); i++) {
		                Unit unit = known.get(i);		                
		                if (unit.team() == myTeam)
		                	processUnit(unit);
		            }
        		}   
	            
        		debug(0, "Round " + currentRound + " took " + (System.currentTimeMillis() - now) + " ms");
        	} catch (Exception e) {
        		//Ignore
        		debug(0, "Caught exception " + e);
        		e.printStackTrace();
        	}
        	gc.nextTurn();
        }
    }
    
    private static void processUnit(Unit unit) {
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
	    		manageRocket(unit);
	    		break;
	    	default:
	    		break;
		}		    
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
    	Planet p = centre.getPlanet();
    	result.add(centre);
    	
    	//The tiles in the resultant circle will be 4 way symmetric along the diagonals and vertices
    	//and 8 way symmetric in other areas

    	//First the horizontal/vertical
    	for (int x=1; x*x<=radiusSq; x++) {
    		if (cx+x < width)
				result.add(new MapLocation(p, cx+x, cy));
			if (cy+x < height)
				result.add(new MapLocation(p, cx, cy+x));
			if (cx-x >= 0)
				result.add(new MapLocation(p, cx-x, cy));
			if (cy-x >= 0)
				result.add(new MapLocation(p, cx, cy-x));
    	}
    	
    	//Now the diagonals
    	for (int x=1; 2*x*x<=radiusSq; x++) {
    		if (cx+x < width) {
    			if (cy+x < height)
    				result.add(new MapLocation(p, cx+x, cy+x));
    			if (cy-x >= 0)
    				result.add(new MapLocation(p, cx+x, cy-x));
    		}
			if (cx-x >= 0) {
				if (cy+x < height)
					result.add(new MapLocation(p, cx-x, cy+x));
				if (cy-x >= 0)
					result.add(new MapLocation(p, cx-x, cy-x));
			}
    	}
    	
    	//Finally the 8 way symmetry
    	for (int x=2; x*x+1<=radiusSq; x++) {
    		for (int y=1; y<x && x*x+y*y<=radiusSq; y++) {
				if (cx+x < width) {
					if (cy+y < height)
						result.add(new MapLocation(p, cx+x, cy+y));
					if (cy-y >= 0)
						result.add(new MapLocation(p, cx+x, cy-y));
				}
				if (cx-y >= 0) {
					if (cy+x < height)
						result.add(new MapLocation(p, cx-y, cy+x));
					if (cy-x >= 0)
						result.add(new MapLocation(p, cx-y, cy-x));
				}
				if (cx-x >= 0) {
					if (cy-y >= 0)
						result.add(new MapLocation(p, cx-x, cy-y));
					if (cy+y < height)
						result.add(new MapLocation(p, cx-x, cy+y));
				}
				if (cx+y < width) {
					if (cy-x >= 0)
						result.add(new MapLocation(p, cx+y, cy-x));				
					if (cy+x < height)
						result.add(new MapLocation(p, cx+y, cy+x));
				}
    		}
    	}
    	
    	return result;
    }
    
    private static ArrayList<MapLocation> allNeighboursOf(MapLocation centre) {
    	ArrayList<MapLocation> result = new ArrayList<MapLocation>();
    	int cx = centre.getX(), cy = centre.getY();
    	int width = (int) map.getWidth(), height = (int) map.getHeight();
    	Planet p = centre.getPlanet();
    	
    	if (cx > 0) {
    		result.add(new MapLocation(p, cx-1, cy));
    		if (cy > 0)
    			result.add(new MapLocation(p, cx-1, cy-1));
    		if (cy+1 < height)
    			result.add(new MapLocation(p, cx-1, cy+1));
    	}
    	if (cy > 0)
			result.add(new MapLocation(p, cx, cy-1));
		if (cy+1 < height)
			result.add(new MapLocation(p, cx, cy+1));
		
		if (cx+1 < width) {
			result.add(new MapLocation(p, cx+1, cy));
    		if (cy > 0)
    			result.add(new MapLocation(p, cx+1, cy-1));
    		if (cy+1 < height)
    			result.add(new MapLocation(p, cx+1, cy+1));
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
    

    /* 
     * Movement wrapper for all units
     * They pick the best move according to their gravity map and move in that direction
     * If the destination is a structure of our - get it to load us
     */
    private static void moveUnit(Unit unit) {
    	int id = unit.id();
    	
    	if (!unit.location().isOnMap() || !gc.isMoveReady(id))
    		return;
    	
    	Direction d = bestMove(unit, getGravityMap(unit.unitType()));   	
    	if (d == null)
    		return; //No where better
    	
    	MapLocation loc = unit.location().mapLocation();
		MapLocation dest = loc.add(d);
		
		if (gc.canMove(id, d)) {
			units.removeUnit(loc);
			gc.moveRobot(id, d);  			
			units.updateUnit(dest);
		} else { //Check to see if there is a structure of ours there
			Unit structure = units.unitAt(dest);
			if (structure != null && gc.canLoad(structure.id(), id)) {
				if (structure.unitType() == UnitType.Factory || myPlanet == Planet.Mars ||
						allowedOnBoard(structure, unit.unitType()) > 0) {
    				units.removeUnit(loc);
    				gc.load(structure.id(), id);
    				units.updateUnit(dest);
    				debug(2, "Loading " + unit.unitType() + " into " + structure.unitType());
    			}
			}
    	}	
    }
    
    /*
     * attackWeakest
     * 
     * Pick the enemy with the most damage
     * Returns true if we attacked, false if we didn't
     */
    private static boolean attackWeakest(Unit unit) {
    	int id = unit.id();
    	
		if (!unit.location().isOnMap() || !gc.isAttackReady(id))
			return false;
		
		VecUnit inSight = gc.senseNearbyUnitsByTeam(unit.location().mapLocation(), unit.attackRange(), otherTeam);
    	//Pick the enemy with the most damage that is in range

    	long mostDamage = -1;
    	Unit best = null;
    	
    	for (int i=0; i<inSight.size(); i++) {
    		Unit enemy = inSight.get(i);
    		if (gc.canAttack(id, enemy.id()) && enemy.maxHealth() - enemy.health() > mostDamage) {
    			best = enemy;
    			mostDamage = enemy.maxHealth() - enemy.health();
    		}
    	}

    	if (best == null)
    		return false;
    	
		MapLocation where = new MapLocation(myPlanet, best.location().mapLocation().getX(), best.location().mapLocation().getY());
		gc.attack(unit.id(), best.id());
		units.updateUnit(where);
		debug(2, "Unit " + unit.id() + " " + unit.unitType() + " firing on " + best.unitType() + " @ " + where);
		return true;
    }
    
    /*
     * Take out structure first (they don't move)
     * then rangers
     * then other units
     */
    private static MapLocation bestSnipeTarget(List<Unit> enemies) {
    	Unit best = null;
    	for (Unit u: enemies) {
    		if (u.unitType() == UnitType.Factory || u.unitType() == UnitType.Rocket)
    			return u.location().mapLocation();
    		if (best == null || (best.unitType() != UnitType.Ranger && u.unitType() == UnitType.Ranger))
    			best = u;
    	}
    	
    	if (best == null)
    		return null;
    	
    	return best.location().mapLocation();
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
    	    int x = m.getX(), y = m.getY();
    	    if (status[x][y] == UNSEEN && passable[x][y])
    	    	status[x][y] = OPEN;
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
    		double gravity = points/(distance*distance);
    		List<MapLocation> nextEdge = new LinkedList<MapLocation>();
    		
        	for (MapLocation me: edge) {
        		int x = me.getX(), y = me.getY();
        		if (status[x][y] != CLOSED) {
        			Unit unit = units.unitAt(me);
    				if (unit != null && unit.team() == myTeam &&
    						(match == null || match == unit.unitType())) {
						matchCount++;
						if (distance > 1 && matchCount >= max) { //We have reached the cutoff point
							debug(3, "Ripple match count met: complete at distance " + distance);
							return;
						}
					}
        			
	        		status[x][y] = CLOSED;
	        		debug(4, "Ripple SEEN " + me);

	    			//Score this tile
					gravityMap[x][y] += gravity;
		       		
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
    		case Worker:
    			return 1;
    		case Healer:
    			return 1;
	    	case Mage:
	    	case Ranger:
	    		return 10;
	    	default:
	    		return 1;
    	}
    }
    
    /*
     * Returns how many more of the given unit type are allowed on the rocket
     * After turn 700 we let anyone in!
     */
    private static int allowedOnBoard(Unit rocket, UnitType passenger) {
    	VecUnitID loaded = rocket.structureGarrison();
		
		int space = (int) (rocket.structureMaxCapacity() - loaded.size());
		if (space <= 0)
			return 0;
		
    	if (currentRound > 700) //Flood is soon - let anyone on
    		return space;
    	
    	//Count how many units of our type are already on board
		int count = 0;
		for (long i=0; i<loaded.size(); i++) {
			Unit u = gc.unit(loaded.get(i));
			if (u.unitType() == passenger)
				count++;
		}

		if (count > maxUnitsOnRocket(passenger)) //Allocation for this unit type is already taken
			return 0;
		
		return Math.min(maxUnitsOnRocket(passenger)- count, space);
    }
    
    /*
     * addRockets
     * 
     * Adds in gravity for each active rocket on earth looking for passengers
     */
    private static void addRockets(double[][] map, UnitType match) {
    	//Add Rockets that are ready to board and have space
		if (myPlanet == Planet.Earth) {
    		for (Unit r: rockets) {
    			int allowed = allowedOnBoard(r, match);
    			if (allowed > 0)
    				ripple(map, r.location().mapLocation(), currentRound*100, match, allowed);
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
    
    /******************************************************************************************
     * The next section implements the specific gravity maps for each unit type
     * 
     * All combat units head towards enemy units
     * Healers head towards damaged allies
     * Workers are the most complex as they take into account karbonite deposits and buildings
     * All units head towards rockets when built and avoid danger zone consisting of
     * - Area around rockets about to land or take off
     * - Attack area around enemy units
     ******************************************************************************************/
      
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
    			//We only want the tiles not in our no-fire zone
    			if (m.distanceSquaredTo(enemyLoc) > 10)
    				targets.add(m);
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
    		for (MapLocation m:allLocationsWithin(enemyLoc, 50)) {
    			//We don't want tiles adjacent to the enemy as we will take splash damage
    			if (m.distanceSquaredTo(enemyLoc) > 8)
    				targets.add(m);
    		}
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
    		targets.addAll(allLocationsWithin(u.location().mapLocation(), 30));
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
		ripple(workerMap, karboniteLocation, 50, UnitType.Worker, workerCount);
		
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
		ripple(workerMap, targets, 200, UnitType.Worker, workerCount);
		
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
    
    /**************************************************************************************
     * Game Engine routines to process the current state and make decisions
     * Where possible information is processed once and cached for best performance
     **************************************************************************************/
    
    /*
     * Scan the map data and record the locations of karbonite, the number of passable neighbours each tile has
     * and return the total karbonite on the map
     * 
     * This is called once on the first turn
     */
    private static void scanMap() {
    	int w = (int) map.getWidth(), h = (int) map.getHeight();
    	int totalKarbonite = 0; //How much Karbonite is available on the starting map
       
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
    	
    	if (totalKarbonite < 100)
    		maxWorkers = 4;
    	else if (totalKarbonite < 300)
    		maxWorkers = 8;
    	else
    		maxWorkers = 16;
    	
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
    private static int totalLandUnits = 0; //Updated each turn
    private static boolean saveForFactory = false;
    private static boolean saveForRocket = false;
    
    /*
     * Loop through the units we are aware of and update our cache
     * Called once each turn
     */
    private static void updateUnits() {  	
        units.updateCache(); //All the units we can see on the map (but not the ones in garrisons)
        unitsInSpace = gc.unitsInSpace(); //All the units in space
    	Arrays.fill(myLandUnits, 0);
    	Arrays.fill(mySpaceUnits, 0);
    	totalLandUnits = 0;
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
            		totalLandUnits++;
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
	            		case Knight: //Increase radius to 8 to account for them moving then attacking
	            		case Mage:
	            			if (unit.attackHeat() < 20) { //Unit will be able to attack next turn
		            			for (MapLocation m:allLocationsWithin(unit.location().mapLocation(), Math.max(8, unit.attackRange()))) {
		            				int x = m.getX(), y = m.getY();
		            				if (unit.unitType() != UnitType.Ranger || m.distanceSquaredTo(unit.location().mapLocation()) > 10)
		            					danger[x][y] = true;
		            			}
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
    	
    	/*
    	 * Work out our build priorities
    	 */
    	int factoriesNeeded = 2 - myLandUnits[UnitType.Factory.ordinal()];
		int unitsToTransport = (myLandUnits[UnitType.Worker.ordinal()] + 
				myLandUnits[UnitType.Ranger.ordinal()] +
				myLandUnits[UnitType.Mage.ordinal()] +
				myLandUnits[UnitType.Knight.ordinal()] +
				myLandUnits[UnitType.Healer.ordinal()]);
		int rocketsNeeded = ((unitsToTransport+7) / 8) - myLandUnits[UnitType.Rocket.ordinal()];
		saveForFactory = (factoriesNeeded > 0);
		saveForRocket = (currentRound > 150 && rocketsNeeded > 0);
		
    	
    	//Look for any rockets arriving on Mars in the next 10 turns and mark the tiles
    	//around the landing site as dangerous
    	if (myPlanet == Planet.Mars) {
    		for (int r=0; r<10; r++) {
	    		VecRocketLanding landings = gc.rocketLandings().landingsOn(currentRound+r);
	    		for (int l=0; l<landings.size(); l++) {
	    			MapLocation site = landings.get(l).getDestination();
	    			danger[site.getX()][site.getY()] = true;
	    			for (MapLocation m:allNeighboursOf(site))
	    				danger[m.getX()][m.getY()] = true;
	    		}
    		}
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
    	}
    }
    
    private static void manageWorker(Unit unit) {
    	if (!unit.unitType().equals(UnitType.Worker))
    		return;
    	
    	int id = unit.id();
    	
    	if (!unit.location().isOnMap())
    		return;
    	
		//Do we want to move to a better location
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

    	//Check to see if we should replicate
    	if (dir != null && myLandUnits[UnitType.Worker.ordinal()] + mySpaceUnits[UnitType.Worker.ordinal()] < maxWorkers) {
        	if (gc.canReplicate(id, dir)) {
        		gc.replicate(id, dir);
        		debug(2, "worker replicating");
        		myLandUnits[UnitType.Worker.ordinal()]++;
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
			if (saveForRocket &&
					gc.karbonite() >= bc.bcUnitTypeBlueprintCost(UnitType.Rocket) &&
					gc.canBlueprint(id, UnitType.Rocket, dir)) {
				gc.blueprint(id, UnitType.Rocket, dir);
				units.updateUnit(loc.add(dir));
				debug(2, "worker blueprinting rocket");
				myLandUnits[UnitType.Rocket.ordinal()]++;
				saveForRocket = false;
			}
			if (saveForFactory &&
					gc.karbonite() >= bc.bcUnitTypeBlueprintCost(UnitType.Factory) &&
					gc.canBlueprint(id, UnitType.Factory, dir)) {
				gc.blueprint(id, UnitType.Factory, dir);
				units.updateUnit(loc.add(dir));
				debug(2, "worker blueprinting factory");
				myLandUnits[UnitType.Factory.ordinal()]++;
				saveForFactory = false;
			}
		}
    }
    
    /***********************************************************************************
     * Handlers for managing launch times and destinations
     * We give a rocket 50 turns before it takes off to give units time to get aboard
     * We head off early if
     * - we are full
     * - we are partially full and are taking damage
     * - it is turn 749 (Flood next turn)
     * 
     * Mars can be split into disjoint zones so we iterate through the zones as we send rockets
     * to ensure we populate all zones
     *************************************************************************************/
    
    private static int marsZone = 0; //This is the zone we want to land in next
    private static HashMap<Integer, Long> launchRound = new HashMap<Integer, Long>(); //List of scheduled launches (rocket id, round)
 
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
	
    /*
     * Pick a random location in the current zone
     * Valid landing sites are removed from the zone once a rocket has taken off
     * TODO - allow Mars to tell us that a landing site is valid again (if our rocket was destroyed)
     */
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
    	long round = 749; //TODO - find constant and use it
    	if (u.structureIsBuilt() == 0)
    		return round; 
    	
    	int id = u.id();
    	if (launchRound.containsKey(id))
    		return launchRound.get(id);
    	
    	
    	if (currentRound + 50 < round)
    		round = currentRound + 50;
    	
    	launchRound.put(id, round);
    	debug(3, "Rocket id " + id + " will launch on round " + round);
    	return round;
    }
    
    /*********************************************************************************
     * Routines to manage each type of unit - one per type
     * They use their gravity maps to inform movement
     * Combat units attack the most damaged enemy in range
     * All units have specialist code for their unique abilities
     *********************************************************************************/
    
    
    /*
     * Rockets leave Earth when full or we reach the launch time and then
     * unload all their occupants as fast as possible on Mars
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
    	} else { //On Mars our only job is to unload units
    		if (unit.structureGarrison().size() > 0) {
	    		for (Direction dir:Direction.values()) {
					if (dir != Direction.Center && gc.canUnload(id, dir)) {
						MapLocation where = unit.location().mapLocation().add(dir);
	    				gc.unload(id, dir);   		    	
	    		    	units.updateUnit(where);
	    		    	debug(2, "unloading from rocket");
	    		    	processUnit(units.unitAt(where)); //Give it a chance to act	    		    	
					}
				}
	    		units.updateUnit(unit.location().mapLocation());
    		}
    	}
    }
    
    /*
     * Factories produce combat units and allow other units to move through them
     * They need to make an informed choice about what to build and whether they should
     * hold off building units in order to save up for rockets
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
    	
    	if (!saveForFactory && !saveForRocket) {
	    	/*
	    	 * Produce units
	    	 * 
	    	 * Rangers and overpowered right now - make many!
	    	 * Throw in some mages later on once upgraded and some healers
	    	 */
    		
	    	UnitType produce = UnitType.Ranger;
	    	
	    	if (myLandUnits[UnitType.Worker.ordinal()] == 0)
	    		produce = UnitType.Worker;
	    	if (currentRound > 600) { //Prepare for mars invasion
		    	produce = UnitType.Mage;
		    	if (3 * myLandUnits[UnitType.Healer.ordinal()] < myLandUnits[UnitType.Mage.ordinal()])
		    		produce = UnitType.Healer;
	    	}
	    	
	    	if (gc.canProduceRobot(fid, produce)) {
				gc.produceRobot(fid, produce);
			}
    	}
    }
    
    private static void manageMage(Unit unit) {
    	int id = unit.id();
    	
    	if (!unit.location().isOnMap())
    		return;
    	
    	//Do we want to blink to a better location
    	if (gc.isAttackReady(id) && unit.isAbilityUnlocked() > 0 && gc.isBlinkReady(id)) {
    		MapLocation here = unit.location().mapLocation();
    		//We can blink to best location in sight range
    		updateMageMap();
    		double bestScore = mageMap[here.getX()][here.getY()];
    		MapLocation bestOption = here;
    		for (MapLocation o:allLocationsWithin(here, unit.abilityRange())) {
    			if (mageMap[o.getX()][o.getY()] > bestScore && passable[o.getX()][o.getY()] &&
    					gc.canSenseLocation(o) && !gc.hasUnitAtLocation(o)) {
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
    	
    	attackWeakest(unit);	   	
    	
        moveUnit(unit);
        
    	attackWeakest(unit);
    }
    
    /*
     * manageRanger
     * 
     * As a combat unit we attack enemies and scout
     */
    private static void manageRanger(Unit unit) {
    	if (!unit.location().isOnMap())
    		return;
    	
    	/*
    	 * Sniping
    	 * To start a snipe we must 
    	 * a) have upgraded
    	 * b) be in a safe location
    	 * c) have no valid targets
    	 * d) not be already sniping
    	 * e) be aware of a valid snipe target (Prefer other rangers, then structures as they don't move)
    	 */   
    	boolean sniping = (unit.rangerIsSniping() > 0);
    	MapLocation here = unit.location().mapLocation();
    	boolean inDanger = danger[here.getX()][here.getY()];
    	if (!attackWeakest(unit) && unit.isAbilityUnlocked() > 0 && !inDanger &&
    			!sniping && gc.isBeginSnipeReady(unit.id()) && enemies.size() > 0) {
    		MapLocation target = bestSnipeTarget(enemies);
    		if (gc.canBeginSnipe(unit.id(), target)) {
    			gc.beginSnipe(unit.id(), target);
    			sniping = true;
    			debug(0, "Sniping on " + target);
    		}
    	}
    	
    	if (!sniping || (gc.isMoveReady(unit.id()) && inDanger)) {
	        moveUnit(unit);        	
	        attackWeakest(unit);
    	}
    }
    
    private static void manageKnight(Unit unit) {
    	if (!unit.location().isOnMap())
    		return;
    	
    	attackWeakest(unit); 	
    	
    	//Do we want to move to a better location
        moveUnit(unit);
        	
       attackWeakest(unit);
    }
    
    /*
     * manageHealer
     * 
     * We don't fight so we heal anyone in range
     */
    private static void manageHealer(Unit unit) {
    	if (!unit.location().isOnMap())
    		return;
    	
        moveUnit(unit);
    	
    	if (gc.isHealReady(unit.id())) {
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
    	
    	//TODO - Overcharge
    }
}