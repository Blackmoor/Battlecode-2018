// import the API.
// See xxx for the javadocs.
import java.util.LinkedList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
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
    private static int maxWorkers; //How many workers we need to mine the karbonite
    private static Random randomness = new Random(74921);
    private static LinkedList<MapLocation> karboniteLocation = new LinkedList<MapLocation>(); //Initialised with starting values and updated as we sense tiles with new values
    
    private static final long LastRound = 1000;
    private static final long EvacuationRound = 600;
    private static final long FloodTurn = 749;
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
	        gc.queueResearch(UnitType.Healer); // Better Healing (25 Turns)
	        gc.queueResearch(UnitType.Healer); // Better Healing (100 Turns)	        
	        gc.queueResearch(UnitType.Ranger); // Increase Vision Range (100 Turns)
	        gc.queueResearch(UnitType.Rocket); // Allows us to build rockets (100 turns)
	        gc.queueResearch(UnitType.Ranger); // Snipe (200 Turns)	
	        gc.queueResearch(UnitType.Healer); // Overcharge (200 Turns)	        
	        gc.queueResearch(UnitType.Rocket); // Faster travel
	        gc.queueResearch(UnitType.Rocket); // Increased Capacity (12)
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
		            updateUnits();
		            updateKarbonite();
		            debug(1, "Karbonite store = " + gc.karbonite() + " Map has " + karboniteLocation.size() + " sources");
		            
		            VecUnit known = units.allUnits();
		            for (int i=0; i<known.size(); i++) {
		            	Unit u = known.get(i);
		            	if (u.team() == myTeam)
		            		processUnit(u);
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
    	//long now = System.currentTimeMillis();
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
    	//debug(0, "Processed " + unit.unitType() + " at " + unit.location() + " in " + (System.currentTimeMillis() - now) + " ms");
    }
 
    private static void debug(int level, String s) {
    	if (level <= debugLevel)
    		System.out.println(s);
    }
    
    /***************************************************************************************
     * Utility functions
     * These are sometimes local versions of the gc routines that perform better
     ***************************************************************************************/
    private static LinkedList<MapLocation> allLocationsWithin(MapLocation centre, long min, long max) {
    	LinkedList<MapLocation> result = new LinkedList<MapLocation>();
    	int cx = centre.getX(), cy = centre.getY();
    	
    	if (max == 100 && info[cx][cy].within100 != null)
    		return info[cx][cy].within100;
    	if (max == 70 && info[cx][cy].within70 != null)
    		return info[cx][cy].within70;
    	if (max == 50) {
    		if (min == 10 && info[cx][cy].within50_10 != null)
    			return info[cx][cy].within50_10;
    		if (info[cx][cy].within50 != null)
    			return info[cx][cy].within50;
    	}
    	if (max == 30) {
    		if (min == 8 && info[cx][cy].within30_8 != null)
    			return info[cx][cy].within30_8;
    		if (info[cx][cy].within30 != null)
    			return info[cx][cy].within30;
    	}   		
    	
    	int width = (int) map.getWidth(), height = (int) map.getHeight();
    	Planet p = centre.getPlanet();
    	
    	if (min > 0)
    		result.add(centre);
    	
    	//The tiles in the resultant circle will be 4 way symmetric along the diagonals and vertices
    	//and 8 way symmetric in other areas

    	//First the horizontal/vertical
    	for (int x=1; x*x<=max; x++) {
    		if (x*x <= min)
    			continue;
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
    	for (int x=1; 2*x*x<=max; x++) {
    		if (2*x*x <= min)
    			continue;
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
    	for (int x=2; x*x+1<=max; x++) {
    		if (x*x+1 <= min)
    			continue;
    		for (int y=1; y<x && x*x+y*y<=max; y++) {
    			if (x*x+y*x<=min)
    				continue;
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
    	
    	if (max == 100)
    		info[cx][cy].within100 = result;
    	if (max == 70)
    		info[cx][cy].within70 = result;
    	if (max == 50) {
    		if (min == 10)
    			info[cx][cy].within50_10 = result;
    		else
    			info[cx][cy].within50 = result;
    	}
    	if (max == 30) {
    		if (min == 8)
    			info[cx][cy].within30_8 = result;
    		else
    			info[cx][cy].within30 = result;
    	}
    	
    	return result;
    }
    
    private static LinkedList<MapLocation> allNeighboursOf(MapLocation centre) {
    	LinkedList<MapLocation> result = new LinkedList<MapLocation>();
    	int cx = centre.getX(), cy = centre.getY();
    	long width = map.getWidth(), height = map.getHeight();
    	
    	if (cx > 0) {
    		result.add(info[cx-1][cy].here);
    		if (cy > 0)
    			result.add(info[cx-1][cy-1].here);
    		if (cy+1 < height)
    			result.add(info[cx-1][cy+1].here);
    	}
    	if (cy > 0)
			result.add(info[cx][cy-1].here);
		if (cy+1 < height)
			result.add(info[cx][cy+1].here);
		
		if (cx+1 < width) {
			result.add(info[cx+1][cy].here);
    		if (cy > 0)
    			result.add(info[cx+1][cy-1].here);
    		if (cy+1 < height)
    			result.add(info[cx+1][cy+1].here);
		}
		return result;
    }
    
    /*
     * Returns an array containing all the passable neighbours of a map location
     * Passable means on the map and not water
     * 
     * Only called by MapInfo to create and cache the results for each location on the map
     */
    private static LinkedList<MapLocation> allPassableNeighbours(MapLocation l) {
    	LinkedList<MapLocation> result = new LinkedList<MapLocation>();
    	for (MapLocation test:info[l.getX()][l.getY()].neighbours) {
    		if (info[test.getX()][test.getY()].passable)
    			result.add(test);
		}
    	
    	return result;
    }
    
    /*
     * Returns true if the unit is ours and a completed factory or rocket
     */
    private static boolean isOurStructure(Unit u) {
    	if (u == null || u.team() != myTeam)
    		return false;
    	
    	return ((u.unitType() == UnitType.Factory || u.unitType() == UnitType.Rocket)
    			&& u.structureIsBuilt() > 0);
    }
    
    /*
     * Returns an array containing all the open neighbours of a map location
     * Open means on the map and not water and doesn't contain a unit
     */
    private static LinkedList<MapLocation> allOpenNeighbours(MapLocation l) {
    	LinkedList<MapLocation> result = new LinkedList<MapLocation>();
    	
    	for (MapLocation test:info[l.getX()][l.getY()].passableNeighbours) {
    		if (units.unitAt(test) == null)
				result.add(test);
		}
    	
    	return result;
    }
    
    /*
     * Returns an array containing all the neighbours of a map location we can move to
     * Open means on the map and not water and doesn't contain a blocking unit
     */
    private static LinkedList<MapLocation> allMoveNeighbours(MapLocation l) {
    	LinkedList<MapLocation> result = new LinkedList<MapLocation>();
    	for (MapLocation test:info[l.getX()][l.getY()].passableNeighbours) {
    		Unit u = units.unitAt(test);
			if (u == null || isOurStructure(u))
				result.add(test);
		}
    	
    	return result;
    }
    
    /*
     * A faster version of the sense routine that uses our cache of units
     */
    private static LinkedList<Unit> senseNearbyUnitsByTeam(MapLocation centre, long radius, Team team) {
    	LinkedList<Unit> result = new LinkedList<Unit>();
    	
    	for (MapLocation m: allLocationsWithin(centre, -1, radius)) {
    		Unit u = units.unitAt(m);
    		if (u != null && u.team() == team) {
    			result.add(u);
    		}
    	}
    	return result;
    }

    /* 
     * Movement wrapper for all units
     * They pick the best move according to their gravity map and move in that direction
     * If the destination is a structure of our - get it to load us
     */
    private static Unit moveUnit(Unit unit) {
    	int id = unit.id();
    	
    	if (!unit.location().isOnMap() || !gc.isMoveReady(id))
    		return unit;
    	
    	Direction d = bestMove(unit, getGravityMap(unit.unitType()), false);   	
    	if (d == null)
    		return unit; //No where better
    	
    	MapLocation loc = unit.location().mapLocation();
		MapLocation dest = loc.add(d);
		
		if (gc.canMove(id, d)) {
			units.removeUnit(loc);
			gc.moveRobot(id, d);  			
			unit = units.updateUnit(id);
		} else { //Check to see if there is a structure of ours there
			Unit structure = units.unitAt(dest);
			if (structure != null && gc.canLoad(structure.id(), id)) {
				units.removeUnit(loc);
				gc.load(structure.id(), id);
				units.updateUnit(structure.id());
				unit = gc.unit(unit.id());
				debug(2, "Loading " + unit.unitType() + " into " + structure.unitType());
			}
    	}
		return unit;
    }
    
    /*
     * attackWeakest
     * 
     * Pick the enemy with the most damage
     * Returns true if we attacked, false if we didn't
     */
    private static Unit attackWeakest(Unit unit) {
    	int id = unit.id();
    	
		if (!unit.location().isOnMap() || !gc.isAttackReady(id))
			return unit;
		
		//Pick the enemy with the most damage that is in range
    	long mostDamage = -1;
    	Unit best = null;
    	
    	for (Unit enemy: senseNearbyUnitsByTeam(unit.location().mapLocation(), unit.attackRange(), otherTeam)) {
    		if (gc.canAttack(id, enemy.id()) && enemy.maxHealth() - enemy.health() > mostDamage) {
    			best = enemy;
    			mostDamage = enemy.maxHealth() - enemy.health();
    		}
    	}

    	if (best == null)
    		return unit;
    	
		debug(2, unit.unitType() + " firing on " + best.unitType());
		gc.attack(unit.id(), best.id());
		units.updateUnit(best.id());
		return units.updateUnit(id);	
    }
    
    /*
     * Take out structure first (they don't move)
     * then rangers
     * then other units
     * 
     * If there are no enemies - pick a random passable location that is currently not visible
     */
    private static MapLocation bestSnipeTarget(LinkedList<Unit> enemies) {
    	Unit best = null;
    	for (Unit u: enemies) {
    		if (u.unitType() == UnitType.Factory || u.unitType() == UnitType.Rocket)
    			return u.location().mapLocation();
    		if (best == null || (best.unitType() != UnitType.Ranger && u.unitType() == UnitType.Ranger))
    			best = u;
    	}
    	
    	if (best != null)
    		return best.location().mapLocation();
    	
    	//No enemies - try a random location
    	if (exploreZone.size() > 0) {
    		int r = randomness.nextInt(exploreZone.size());
    		return exploreZone.get(r);
    	}
    	
    	return null;
    }
    
    private static int bestJavelinTarget(Unit knight) {
    	int best_id = -1;
    	int mostDamage = -1;
    	
    	for (Unit enemy: senseNearbyUnitsByTeam(knight.location().mapLocation(), knight.attackRange(), otherTeam)) {
    		if (enemy.maxHealth() - enemy.health() > mostDamage) {
    			mostDamage = (int)(enemy.maxHealth() - enemy.health());
    			best_id = enemy.id();
    		}
    	}
    	return best_id;
    }
    
    /**************************************************************************************
     * Gravity Map functions
	 * All movement is based on gravity wells generated by points of interest
     * The gravity well maps are stored in arrays that map onto the Planet map
     * High scores are more interesting - units move to an adjacent tile with a higher score if possible
     * 
     * We create these maps each turn that a unit needs to move and only as needed
     **************************************************************************************/
    
    private static MapInfo[][] info = null; //All neighbours of each location
    private static double[][] workerMap = null;
    private static double[][] mageMap = null;
    private static double[][] rangerMap = null;
    private static double[][] healerMap = null;
    private static double[][] knightMap = null;
    private static double[][][] allMaps = null;
    
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
     * If called with a null gravity map then we actually add to all gravity maps
     */
    public static void ripple(double[][] gravityMap, LinkedList<MapLocation> edge, double points, UnitType match, int max) {
    	boolean[][] processed = new boolean[(int)map.getWidth()][(int)map.getHeight()];
    	int distance = 0; //How far from the source are we
    	int matchCount = 0; //How many units of the right type have we seen
    	
    	debug(3, "ripple: starting points " + edge.size() + " value " + points + " stop when " + max + " " + match);
    	
    	/*
    	 * Mark all valid locations as processed (seen)
    	 * Remove duplicates
    	 */
    	for (Iterator<MapLocation> iterator = edge.iterator(); iterator.hasNext();) {
    	    MapLocation m = iterator.next();
    	    int x = m.getX(), y = m.getY();
    	    if (!processed[x][y])
    	    	processed[x][y] = true;
    	    else
    			iterator.remove();
    	}

    	/*
    	 * This is a modified Breadth First Search
    	 * Since we want to know the distance from the source we maintain a current open list (edge)
    	 * and build up the next open list (nextEdge). When edge is processed (empty) we increment the distance and switch to the nextEdge
    	 */
    	while (!edge.isEmpty()) {
    		distance++;
    		double gravity = points/(distance*distance);
    		LinkedList<MapLocation> nextEdge = new LinkedList<MapLocation>();
    		
        	for (MapLocation me: edge) {
        		int x = me.getX(), y = me.getY();
    			Unit unit = units.unitAt(me);
				if (unit != null && unit.team() == myTeam &&
						(match == null || match == unit.unitType())) {
					matchCount++;
					if (distance > 1 && matchCount >= max) { //We have reached the cutoff point
						debug(3, "Ripple match count met: complete at distance " + distance);
						return;
					}
				}

    			//Score this tile
        		if (gravityMap != null)
        			gravityMap[x][y] += gravity;
        		else { //Rockets
        			for (double[][] m:allMaps) //Add to all maps but ignore workers before round 700
	        			if (m != workerMap || currentRound > 700)
	        				m[x][y] += gravity;
        		}
	       		
    			//We add adjacent tiles to the next search if they are traversable
				for (MapLocation t:info[me.getX()][me.getY()].passableNeighbours) {
	    			if (!processed[t.getX()][t.getY()]) {
		    			nextEdge.add(t);
		    			processed[t.getX()][t.getY()] = true;
		    			debug(4, "ripple Added " + t);
	    			}
	    		}
    		}
    		debug(4, "Ripple distance " + distance + " edge size = " + nextEdge.size());
    		
    		edge = nextEdge;
    	}
    	
    	debug(3, "Ripple queue empty: complete at distance " + distance);
    }
    
    public static void ripple(double[][] gravityMap, MapLocation t, double points, UnitType match, int max) {
    	LinkedList<MapLocation> edge = new LinkedList<MapLocation>();
    	edge.add(t);

    	ripple(gravityMap, edge, points, match, max);
    }
    
    public static void ripple(double[][] gravityMap, int x, int y, double points, UnitType match, int max) {
    	LinkedList<MapLocation> edge = new LinkedList<MapLocation>();
    	edge.add(info[x][y].here);

    	ripple(gravityMap, edge, points, match, max);
    }
    
    /*
     * Fill all the gravity maps with random noise
     * Add in the danger zones for all but the knight map
     * Finally run a special version of ripple for each rocket to call in the required units to each one
     */
    private static void initGravityMaps() {   	 	
		for (double[][] me:allMaps) {
			boolean ignoreDanger = (me == knightMap || (me == rangerMap && currentRound % 10 == 0));
	    	for (int x=0; x<map.getWidth(); x++) {
	    		for (int y=0; y<map.getHeight(); y++) {
		    			me[x][y] = randomness.nextDouble() / 10000.0;
		    			//To ensure we don't build up too many units we ignore the danger zones occasionally
		    			//and rely on our healers in between	    			
		    			if (!ignoreDanger)
		    				me[x][y] -= danger[x][y];
		    	}
	    	}
    	}
    	
    	//Add Rockets that are ready to board and have space
    	//We only broadcast when we have spare units (more combat units than map w+h)
    	//Or it is near the flood (turn 600 onwards)
    	int totalCombatForce = myLandUnits[UnitType.Knight.ordinal()] +
    							myLandUnits[UnitType.Ranger.ordinal()] +
    							myLandUnits[UnitType.Mage.ordinal()];
    	int desiredUnits = (int)(map.getWidth() + map.getHeight()) / 2;
    	if (conquored)
    		desiredUnits = 0;
    	int passengers = totalCombatForce - desiredUnits;
    	
    	if (myPlanet == Planet.Earth) {
	    	if (conquored || currentRound > EvacuationRound) {
	    		LinkedList<MapLocation> allRockets = new LinkedList<MapLocation>();
	    		for (Unit r: rockets)
	    			allRockets.add(r.location().mapLocation());
	    		ripple(null, allRockets, 10000000, null, 1000); //Shout really loudly to all units
	    	} else if (passengers > 0) {
	    		for (Unit r: rockets) {
	    			int request = Math.min(passengers,  (int)(r.structureMaxCapacity() - r.structureGarrison().size()));
	    			ripple(null, r.location().mapLocation(), currentRound*100, null, passengers);
	    			passengers -= request;
	    			if (passengers <= 0)
	    				break;
	    		}
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
    	int rangerCount = myLandUnits[UnitType.Ranger.ordinal()];
    	
    	//Add enemies
    	LinkedList<MapLocation> targets = new LinkedList<MapLocation>();
    	for (Unit u:enemies) {
    		//We want to be at our attack distance from each enemy
    		MapLocation enemyLoc = u.location().mapLocation();
    		targets.addAll(allLocationsWithin(enemyLoc, 10, 50));
    	}

    	ripple(rangerMap, targets, 30, UnitType.Ranger, rangerCount);

    	if (enemies.size() == 0)
    		ripple(rangerMap, exploreZone, 30, UnitType.Ranger, rangerCount);
    }
    
    private static void updateMageMap() {
    	if (mageMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	mageMapLastUpdated = currentRound;
    	int mageCount = myLandUnits[UnitType.Mage.ordinal()];
    	
    	//Add enemies
    	LinkedList<MapLocation> targets = new LinkedList<MapLocation>();
    	for (Unit u:enemies) {
    		//We want to be at our attack distance from each enemy
    		MapLocation enemyLoc = u.location().mapLocation();
    		targets.addAll(allLocationsWithin(enemyLoc, 8, 30));
    	}
    	ripple(mageMap, targets, 30, UnitType.Mage, mageCount);
    }
    
    /*
     * Healers need to move towards damaged allies (not structures)
     * Like all other units they will board waiting rockets
     */
    private static void updateHealerMap() {
    	if (healerMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	healerMapLastUpdated = currentRound;
    	int healerCount = myLandUnits[UnitType.Healer.ordinal()];
    	
    	//Add damaged units
    	ripple(healerMap, unitsToHeal, 100, UnitType.Healer, healerCount);
    }
    
    private static void updateKnightMap() {
    	if (knightMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	knightMapLastUpdated = currentRound;
    	int knightCount = myLandUnits[UnitType.Knight.ordinal()];
    	
    	LinkedList<MapLocation> targets = new LinkedList<MapLocation>();
    	//Add enemies
    	for (Unit u:enemies)
    		targets.add(u.location().mapLocation());  	
    	ripple(knightMap, targets, 30, UnitType.Knight, knightCount);
    	
    	if (enemies.size() == 0 && myLandUnits[UnitType.Knight.ordinal()] > 20)
    		ripple(knightMap, exploreZone, 30, UnitType.Knight, knightCount);
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
		int workerCount = myLandUnits[UnitType.Worker.ordinal()];
		
		//Add Karbonite deposits
		ripple(workerMap, karboniteLocation, 10, UnitType.Worker, workerCount);
		
		//Add blueprints and damaged buildings
		ripple(workerMap, unitsToBuild, 200, UnitType.Worker, Math.min(workerCount, unitsToBuild.size()));
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
     * Scan the map data and record the locations of karbonite and the neighbours each tile has
     * 
     * This is called once on the first turn
     */
	private static void scanMap() {
    	int w = (int) map.getWidth(), h = (int) map.getHeight();
    	int turnsToMine = 0; //How many rounds it would take 1 worker to mine (at 3 per turn)
       
    	info = new MapInfo[w][h];
    	
    	rangerMap = new double[w][h];
    	mageMap = new double[w][h];
    	healerMap = new double[w][h];
    	knightMap = new double[w][h];
    	workerMap = new double[w][h];       
        allMaps = new double[][][] { mageMap, rangerMap, workerMap, knightMap, healerMap };
    	
        /*
         * Create and cache a MapLocation for each location on the map and whether it is passable and has karbonite
         */
        for (int x = 0; x<map.getWidth(); x++)
    		for (int y=0; y<map.getHeight(); y++) {
    			MapLocation m = new MapLocation(myPlanet, x, y);
    			info[x][y] = new MapInfo(m, (map.isPassableTerrainAt(m) > 0));
    			if (map.initialKarboniteAt(m) > 0) {
    				karboniteLocation.add(m);
    				turnsToMine += (map.initialKarboniteAt(m) + 2) / 3;
    			}   
    		}
    			
        /*
         * Now store all the neighbours of each location and a subset of that (passable locations) for quick access later
         */
    	for (int x = 0; x<map.getWidth(); x++) {
    		for (int y=0; y<map.getHeight(); y++) {
    			MapLocation here = info[x][y].here;
    			info[x][y].neighbours = allNeighboursOf(here);
    			info[x][y].passableNeighbours = allPassableNeighbours(here);   						
    		}
    	}
    	
    	maxWorkers = Math.max(8, turnsToMine / 100); //We always want 8 for building rockets and factories
    	debug(1, "We need " + maxWorkers + " workers on " + myPlanet);
    	
    	//TODO - analyse asteroids deposits to see if we should send an early rocket to Mars
    	//This could affect our research order if we want to get there really quickly
    	
    	mars = new MapAnalyser(gc);
    }   
    
    /*
     * Given a gravity map we find the highest scoring tile adjacent to us
     * This could be one of our static buildings or an empty tile
     * If the unit supplied is a structure we are not trying to move it - but unload a unit from it
     * In this case we cannot pick directions containing another structure
     */
    private static Direction bestMove(Unit t, double[][] gravityMap, boolean move) {
    	Direction best = null;
    	
    	if (!t.location().isOnMap())
    		return null;
    	
    	MapLocation myLoc = t.location().mapLocation();
    	boolean isStructure =  (t.unitType() == UnitType.Factory || t.unitType() == UnitType.Rocket);   	
    	double bestScore = (move?-100000:gravityMap[myLoc.getX()][myLoc.getY()]);
    	LinkedList<MapLocation> options = null;
    	
    	debug(4, "bestMove from " + myLoc + " current score " + bestScore);
		if (isStructure)
			options = allOpenNeighbours(myLoc);
		else
			options = allMoveNeighbours(myLoc);
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
    		if (visible[m.getX()][m.getY()]) {
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
    private static LinkedList<MapLocation> unitsToBuild = new LinkedList<MapLocation>(); //List of current blueprints that need building
    private static LinkedList<MapLocation> unitsToHeal = new LinkedList<MapLocation>(); //List of units that need healing
    private static LinkedList<Unit> rockets = new LinkedList<Unit>(); //List of rockets (to Load into if on Earth, or unload from on Mars)
    private static LinkedList<Unit> enemies = new LinkedList<Unit>(); //List of all enemy units in sight
    private static int[][] danger; //Array (x,y) of map locations and how much damage a unit would take there
    private static boolean[][] visible; //Array (x,y) of map locations: true we can see (sense) it
    private static boolean saveForFactory = false;
    private static boolean saveForRocket = false;
    private static LinkedList<MapLocation> exploreZone = new LinkedList<MapLocation>(); //All locs that are not visible but next to a visible location
    private static boolean conquored = false; //Set to true on Earth if we can see all the map and no enemies
    
    /*
     * Loop through the units we are aware of and update our cache
     * Called once each turn
     */
    private static void updateUnits() {
        units.updateCache(); //All the units we can see
        unitsInSpace = gc.unitsInSpace(); //All the units in space
    	Arrays.fill(myLandUnits, 0);
    	Arrays.fill(mySpaceUnits, 0);
    	unitsToBuild.clear();
    	unitsToHeal.clear();
    	rockets.clear();
    	enemies.clear();
    	exploreZone.clear();
    	danger = new int[(int) map.getWidth()][(int) map.getHeight()];
    	visible = new boolean[(int) map.getWidth()][(int) map.getHeight()];
    	
    	VecUnit known = units.allUnits();
    	for (int i = 0; i < known.size(); i++) {
            Unit unit = known.get(i);
            
            if (unit.location().isOnMap()) {
            	if (unit.team() == myTeam) {
            		MapLocation here = unit.location().mapLocation();
            		myLandUnits[unit.unitType().ordinal()]++;
            		
            		//Update visibility
            		LinkedList<MapLocation> within = null;           		
            		if (unit.visionRange() == 2)
            			within = info[here.getX()][here.getY()].neighbours;
            		else
            			within = allLocationsWithin(here, -1, unit.visionRange());
            		for (MapLocation m: within) {
        				int x = m.getX(), y = m.getY();
        				visible[x][y] = true;	
        			}
            		
            		if (unit.unitType().equals(UnitType.Factory) || unit.unitType().equals(UnitType.Rocket)) {
            			if (unit.structureIsBuilt() == 0 || unit.health() < unit.maxHealth())
            				unitsToBuild.addAll(info[here.getX()][here.getY()].passableNeighbours);
	
            			if (unit.structureIsBuilt() > 0 && unit.unitType().equals(UnitType.Rocket) && unit.rocketIsUsed() == 0)
            				rockets.add(unit);
            			
            			VecUnitID garrison = unit.structureGarrison();
            			for (int j=0; j<garrison.size(); j++) {
            				int id = garrison.get(j);
            				myLandUnits[gc.unit(id).unitType().ordinal()]++;
            			}
            		} else {
            			if (unit.health() < unit.maxHealth())
            				unitsToHeal.add(unit.location().mapLocation());
            		}
            	} else { //enemies
            		enemies.add(unit);
            		switch (unit.unitType()) {
	            		case Ranger:
	            			for (MapLocation m:allLocationsWithin(unit.location().mapLocation(), unit.rangerCannotAttackRange(), unit.attackRange())) {
	            				int x = m.getX(), y = m.getY();
	            				danger[x][y] += unit.damage();	
	            			}
	            			break;
	            		case Knight: //Increase radius to 30 to account for them moving then attacking
	            		case Mage:
	            			for (MapLocation m:allLocationsWithin(unit.location().mapLocation(), 0, Math.max(30, unit.attackRange()))) {
	            				int x = m.getX(), y = m.getY();
	            				danger[x][y] += unit.damage();
	            			}
	            			break;
	            		default: //Other units cannot attack
	            			break;
            		}
            	}
            }
    	}
    	
    	Collections.sort(rockets, new Comparator<Unit>() {
    		public int compare(Unit r1, Unit r2) {
    			return r1.id() - r2.id();
    		}
    	});
    	
    	for (int i=0; i<unitsInSpace.size(); i++) {
    		Unit unit = unitsInSpace.get(i);
    		mySpaceUnits[unit.unitType().ordinal()]++;
    	}
    	
    	/*
    	 * Produce the list of locations that are unseen but next to a location we can see
    	 * i.e. the border of known space
    	 * If we have no units this list is empty
    	 */
    	if (!conquored && units.allUnits().size() > 0) {
			for (int x=0; x<map.getWidth(); x++) {
				for (int y=0; y<map.getHeight(); y++) {  
					if (!visible[x][y]) { //Unseen - are we adjacent to a visible location
						for (MapLocation m:info[x][y].neighbours) {
							if (visible[m.getX()][m.getY()]) {
								exploreZone.add(info[x][y].here);
								break;
							}
						}
					}
				}
			}
    	}
    	
    	if (!conquored && myPlanet == Planet.Earth && exploreZone.size() == 0 && enemies.size() == 0) {
    		conquored = true;
    		debug(0, "Earth is conquored!");
    	}
    	
    	/*
    	 * Work out our build priorities
    	 */
    	int myCombatUnits = myLandUnits[UnitType.Ranger.ordinal()] +
				myLandUnits[UnitType.Mage.ordinal()] +
				myLandUnits[UnitType.Knight.ordinal()];
		int unitsToTransport = (myLandUnits[UnitType.Worker.ordinal()] + 
				myLandUnits[UnitType.Healer.ordinal()]) +
				myCombatUnits;
		int capacity = (gc.researchInfo().getLevel(UnitType.Rocket) == 3)?12:8;
		int rocketsNeeded = ((unitsToTransport+capacity-1) / capacity) - myLandUnits[UnitType.Rocket.ordinal()];
		saveForFactory = (myLandUnits[UnitType.Worker.ordinal()] > 7 && myLandUnits[UnitType.Factory.ordinal()] < 2) ||
							(currentRound > 50 && myLandUnits[UnitType.Worker.ordinal()] > 0 && myLandUnits[UnitType.Factory.ordinal()] == 0);
		saveForRocket = (myLandUnits[UnitType.Worker.ordinal()] > 0 && gc.researchInfo().getLevel(UnitType.Rocket) > 0 && rocketsNeeded > 0);
		
		if (currentRound > 200)
			maxWorkers = 8;
    	
    	//Look for any rockets arriving on Mars in the next 10 turns and mark the tiles
    	//around the landing site as dangerous
    	if (myPlanet == Planet.Mars) {
    		for (int r=0; r<10; r++) {
	    		VecRocketLanding landings = gc.rocketLandings().landingsOn(currentRound+r);
	    		for (int l=0; l<landings.size(); l++) {
	    			MapLocation site = landings.get(l).getDestination();
	    			debug(2, "Clearing area for landing on round " + (currentRound+r) + " at " + site);
	    			danger[site.getX()][site.getY()] += 100; //TODO find real value from interface
	    			for (MapLocation m:info[site.getX()][site.getY()].neighbours)
	    				danger[m.getX()][m.getY()] += 100;
	    		}
    		}
    	}
    	
    	initGravityMaps(); //Gives each a random noise level and includes and danger areas
    	
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
    	if (!unit.unitType().equals(UnitType.Worker) || !unit.location().isOnMap())
    		return;
    	
    	int id = unit.id();
		//Do we want to move to a better location
        unit = moveUnit(unit);            
        if (!unit.location().isOnMap())
    		return;
        
		MapLocation loc = unit.location().mapLocation();
    	
		//Check to see if we should replicate
    	//Even if we want to replicate we might need to hold off so we can build a factory
    	boolean replicate = (myLandUnits[UnitType.Worker.ordinal()] < maxWorkers);
    	if (myPlanet == Planet.Earth) {
	    	if (saveForFactory)
	    		replicate = false;
    	} else { //Mars
    		if (LastRound - currentRound < 25) //Might as well spend all our karbonite
    			replicate = true;
    	}
    	
    	Direction dir = bestMove(unit, getGravityMap(unit.unitType()), true);
    	if (dir != null && replicate && gc.canReplicate(id, dir)) {
    		gc.replicate(id, dir);
    		debug(2, "worker replicating");
    		myLandUnits[UnitType.Worker.ordinal()]++;
    		Unit newWorker = units.updateUnit(loc.add(dir));
    		processUnit(newWorker);
    	}
        
        //Can we help build or repair something
    	for (MapLocation m:info[loc.getX()][loc.getY()].passableNeighbours) {			
			Unit other = units.unitAt(m);
			if (other != null && other.team() == myTeam &&
					(other.unitType() == UnitType.Factory || other.unitType() == UnitType.Rocket)) {
				if (gc.canBuild(id, other.id())) {
					gc.build(id, other.id());
					debug(2, "worker building");
					return;
				}
				if (other.health() < other.maxHealth() && gc.canRepair(id, other.id())) {
					gc.repair(id, other.id());
  					debug(2, "worker is repairing");
  					return;
				}
			}
		}

		/*
		 * Now check to see if we want to build a factory or a rocket
		 */			
		if (myPlanet == Planet.Earth) {
	    	LinkedList<MapLocation> options = allOpenNeighbours(loc);
	    	dir = null;
	    	
	    	if (!options.isEmpty()) {
	    		//Pick a random open and safe neighbour tile
	    		//TODO - be smarter about blueprint direction - avoid karbonite and tight spaces
	        	int r = randomness.nextInt(options.size());
	        	
	        	for (int i=0; i<options.size(); i++) {
	        		MapLocation test = options.get((i+r)%options.size());      		
	        		if (danger[test.getX()][test.getY()] == 0) {
	        			dir = loc.directionTo(options.get(0));
	        			break; 
	        		}
	        	}
	    	}    	
	    	
	    	if (dir != null) {
	    		MapLocation buildLoc = loc.add(dir);
				if (saveForRocket &&
						gc.karbonite() >= bc.bcUnitTypeBlueprintCost(UnitType.Rocket) &&
						gc.canBlueprint(id, UnitType.Rocket, dir)) {
					gc.blueprint(id, UnitType.Rocket, dir);
					units.updateUnit(buildLoc);
					debug(2, "worker blueprinting rocket");
					myLandUnits[UnitType.Rocket.ordinal()]++;
					saveForRocket = false;
					return;
				}
				
				int myCombatUnits = myLandUnits[UnitType.Ranger.ordinal()] +
						myLandUnits[UnitType.Mage.ordinal()] +
						myLandUnits[UnitType.Knight.ordinal()];
				boolean buildFactory = (saveForFactory || myCombatUnits > 4*myLandUnits[UnitType.Factory.ordinal()]);
				if (buildFactory && gc.karbonite() >= bc.bcUnitTypeBlueprintCost(UnitType.Factory) &&
						gc.canBlueprint(id, UnitType.Factory, dir)) {
					gc.blueprint(id, UnitType.Factory, dir);
					units.updateUnit(buildLoc);
					debug(2, "worker blueprinting factory");
					myLandUnits[UnitType.Factory.ordinal()]++;
					saveForFactory = false;
					return;
				}
	    	}
		}
		
		//Can we Harvest
		for (Direction d: Direction.values()) {
			if (gc.canHarvest(id, d)) {
				gc.harvest(id, d);
				debug(2, "worker harvesting");
				break;
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
	    	found = (marsZone == startZone || mars.zones.get(marsZone).landingSites.size() > 1);
		}
	}
	
    /*
     * Pick a random location in the current zone
     * Valid landing sites are removed from the zone once a rocket has taken off
     * TODO - allow Mars to tell us that a landing site is valid again (if our rocket was destroyed)
     * TODO - allow Mars to request missiles (empty rockets sent to destroy enemy rockets)
     * TODO - prioritise locations with more neighbours for faster unloading
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
    		//Check to see if we are full
    		MapLocation here = unit.location().mapLocation();
    		MapLocation dest = launchDestination();
    		if (dest != null && gc.canLaunchRocket(id, dest)) {
    			long arrivalNow = gc.orbitPattern().duration(currentRound);
    			long arrivalNext = 1+gc.orbitPattern().duration(currentRound+1);
    			boolean full = (unit.structureGarrison().size() >= unit.structureMaxCapacity());
    			boolean takingDamage = (unit.structureGarrison().size() > 0 && unit.health() < unit.maxHealth());
    			if ((full && arrivalNow <= arrivalNext) || takingDamage || currentRound == FloodTurn) {
    				//Load everyone we can
    				for (MapLocation m:info[here.getX()][here.getY()].passableNeighbours) {
    					Unit u = units.unitAt(m);
    					if (u != null && gc.canLoad(id, u.id())) {
    						gc.load(id, u.id());
    						debug(2, "Rocket is loading " + u.unitType() + " before launch");
    						units.removeUnit(m);
    					}
    				}
    				debug(2, "Launching rocket " + id + " to " + dest);
    				gc.launchRocket(id, dest);
    				units.removeUnit(here);
    				mars.zones.get(marsZone).landingSites.remove(dest);
    				nextZone();
    				return;
    			}
    		}
    		
    		long garrisoned = unit.structureGarrison().size();
    		//If we haven't sent out a message for units to come to us we unload them as they are probably passing through
    		if (garrisoned > 0 && rangerMap[here.getX()][here.getY()] < 1000) {
    			while (garrisoned > 0) {
	    			Direction dir = bestMove(unit, workerMap, true);
	    			if (dir != null && gc.canUnload(unit.id(), dir)) {
	    				gc.unload(unit.id(), dir);
	    				debug(2, "Unloading from rocket - passing through");
	    				MapLocation where = unit.location().mapLocation().add(dir);	        			
	    	    		processUnit(units.updateUnit(where));
	    			}
	    			garrisoned--;
    			}
    			unit = units.updateUnit(unit.id()); //Update garrison info
    		}
    	} else { //On Mars our only job is to unload units
    		if (unit.structureGarrison().size() > 0) {
	    		for (Direction dir:Direction.values()) {
					if (dir != Direction.Center && gc.canUnload(id, dir)) {
	    				gc.unload(id, dir);   		    	
	    		    	debug(2, "unloading from rocket");
	    		    	MapLocation where = unit.location().mapLocation().add(dir);
	    	    		processUnit(units.updateUnit(where));
					}
				}
	    		unit = units.updateUnit(unit.id()); //Update garrison info
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
    	long garrisoned = unit.structureGarrison().size();
    	//Unload units if possible
    	while (garrisoned > 0) {
    		LinkedList<MapLocation> options = allOpenNeighbours(unit.location().mapLocation());
    		//Pick a random unload direction - if it is not safe keep looking
    		if (options.size() == 0)
    			break;
    		
    		int r = randomness.nextInt(options.size());
	    	Direction best = null;
	    	for (int i=0; i < options.size(); i++) {
	    		MapLocation m = options.get((i+r)%options.size());
	    		Direction dir = unit.location().mapLocation().directionTo(m);
	    		if (gc.canUnload(fid, dir)) {
		    		if (best == null || danger[m.getX()][m.getY()] == 0)
		    			best = dir;
		    		if (danger[m.getX()][m.getY()] == 0)
		    			break;
	    		}
	    	}
	    	if (best == null)
	    		break;

    		gc.unload(fid, best);
    		debug(2, "unloading from factory");
    		garrisoned--;
    		MapLocation where = unit.location().mapLocation().add(best);
    		processUnit(units.updateUnit(where));
    	}
    	
    	if (!saveForFactory && !saveForRocket) {
	    	/*
	    	 * Produce units
	    	 * 
	    	 * Our default is rangers for combat.
	    	 * The number of healers we need is determined by the state of play.
	    	 * Against Knights healers aren't as useful as more rangers. Against rangers we want more healers
	    	 * 
	    	 * The algorithm is adaptive, i.e. we check to see how many units need healing and create healers accordingly
	    	 */   		
	    	UnitType produce = UnitType.Ranger;
	    	
	    	if (myLandUnits[UnitType.Worker.ordinal()] < Math.min(maxWorkers, myLandUnits[UnitType.Ranger.ordinal()]))
	    		produce = UnitType.Worker;
	    	else if (myLandUnits[UnitType.Healer.ordinal()] < unitsToHeal.size())
	    		produce = UnitType.Healer;
	    	
	    	if (gc.canProduceRobot(fid, produce)) {
				gc.produceRobot(fid, produce);
				debug(2, "Factory starts producing a " + produce);
			}
    	}
    	unit = units.updateUnit(fid); //Update garrison info
    }
    
    private static void manageMage(Unit unit) {
    	int id = unit.id();
    	
    	if (!unit.location().isOnMap())
    		return;
    	
    	//Do we want to blink to a better location
    	if (gc.isAttackReady(id) && gc.isBlinkReady(id)) {
    		MapLocation here = unit.location().mapLocation();
    		//We can blink to best location in sight range
    		updateMageMap();
    		double bestScore = mageMap[here.getX()][here.getY()];
    		MapLocation bestOption = here;
    		for (MapLocation o:allLocationsWithin(here, 0, unit.abilityRange())) {
    			if (mageMap[o.getX()][o.getY()] > bestScore && info[o.getX()][o.getY()].passable &&
    					units.unitAt(o) == null) {
    				bestScore = mageMap[o.getX()][o.getY()];
    				bestOption = o;
    			}
    		}
    		if (here.distanceSquaredTo(bestOption) >= 2) {
    			units.removeUnit(here);
    			gc.blink(id, bestOption);
    			unit = units.updateUnit(id);
    			debug(2, "Mage is blinking to " + bestOption);
    		}
    	}
    	
    	unit = attackWeakest(unit);	   	  	
        unit = moveUnit(unit);
        if (unit.location().isOnMap())
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
    	 * f) not be running to a rocket
    	 * 
    	 * We abort a snipe if
    	 * a) we are in danger
    	 * b) can attack something locally
    	 * 
    	 * There is no point in doing more damage than needed to destroy a target so we need to maintain a list of
    	 * current snipe locations
    	 * 
    	 * If a ranger is doing nothing it might as well snipe at locations that are passable but not visible
    	 */   
    	
    	MapLocation here = unit.location().mapLocation();
    	boolean inDanger = (danger[here.getX()][here.getY()] > 0);
    	boolean canAttack = gc.isAttackReady(unit.id());
    	boolean attacked = false;
    	boolean sniping = (unit.rangerIsSniping() > 0);
    	boolean evacuating = (myPlanet == Planet.Earth && currentRound >= EvacuationRound);
    	
    	//See if we can attack
    	if (canAttack) {
    		unit = attackWeakest(unit);
    		attacked = !gc.isAttackReady(unit.id());
    	}
    	
    	if (sniping && !attacked && !inDanger)
    		return; //Keep sniping
    	
    	//Should we start a snipe?
    	if (!sniping && !attacked && !inDanger && !evacuating && enemies.size() > 0 &&
    			unit.isAbilityUnlocked() > 0 && gc.isBeginSnipeReady(unit.id())) {
    		MapLocation target = bestSnipeTarget(enemies);
    		if (gc.canBeginSnipe(unit.id(), target)) {
    			gc.beginSnipe(unit.id(), target);
    			sniping = true;
    			debug(2, "Sniping on " + target);
    			return;
    		}
    	}
    	
        unit = moveUnit(unit);
        if (unit.location().isOnMap())
        	attackWeakest(unit);
    }
    
    private static void manageKnight(Unit unit) {
    	if (!unit.location().isOnMap())
    		return;
    	
    	unit = attackWeakest(unit); 	   	
        unit = moveUnit(unit);
        if (!unit.location().isOnMap())
    		return;
        	
        unit = attackWeakest(unit);
        
        if (unit.abilityHeat() < 10 && unit.isAbilityUnlocked() > 0) { //Check for javelin targets
        	int target = bestJavelinTarget(unit);
        	if (target > 0 && gc.canJavelin(unit.id(), target)) {
        		gc.javelin(unit.id(), target);
        		debug(2, "Knight is throwing a javelin");
        	}
        }
    }
    
    /*
     * manageHealer
     * 
     * We don't fight so we heal anyone in range
     */
    private static void manageHealer(Unit unit) {
    	if (!unit.location().isOnMap())
    		return;
    	
        unit = moveUnit(unit);
        
        if (!unit.location().isOnMap())
    		return;
    	
    	if (gc.isHealReady(unit.id())) {
	    	for (Unit u: senseNearbyUnitsByTeam(unit.location().mapLocation(), unit.attackRange(), myTeam)) {
	    		if (u.health() < u.maxHealth() && gc.canHeal(unit.id(), u.id())) {
					gc.heal(unit.id(), u.id());
					debug(2, "Healing " + u.unitType() + " @ " + u.location().mapLocation());
					break;
	    		}
	    	}
    	}
    	
    	if (gc.isOverchargeReady(unit.id())) {
    		long mostHeat = 0;
    		Unit bestTarget = null;
	    	for (Unit u:senseNearbyUnitsByTeam(unit.location().mapLocation(), unit.abilityRange(), myTeam)) {
	    		long heat = 0;
	    		switch (u.unitType()) {
	    		case Ranger:
	    		case Knight:
	    		case Mage:
	    			heat = u.attackHeat() + u.movementHeat() + u.abilityHeat();
	    			break;
	    		default: //No point overcharging these units
	    			break;
	    		}	    		
	    		if (heat > mostHeat && gc.canOvercharge(unit.id(), u.id())) {
	    			bestTarget = u;
	    			mostHeat = heat;
	    		}
	    	}
	    	if (bestTarget != null) {
				gc.overcharge(unit.id(), bestTarget.id());
				debug(2, "Overcharging " + bestTarget.unitType() + " @ " + bestTarget.location().mapLocation());
				processUnit(bestTarget);
	    	}
    	}
    }
}