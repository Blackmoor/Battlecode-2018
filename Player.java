// import the API.
// See xxx for the javadocs.
import java.util.LinkedList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
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
    private static MapAnalyser earth;
    
    private static long currentRound; //Updated each turn
    private static UnitCache units; //The list of all known units - updated each turn in the main loop   
    private static VecUnit unitsInSpace; //The list of all units on the way to mars
    private static int maxWorkers; //How many workers we need to mine the karbonite
    private static Random randomness = new Random(74921);
    private static LinkedList<MapLocation> karboniteLocation = new LinkedList<MapLocation>(); //Initialised with starting values and updated as we sense tiles with new values
    private static long[][] karboniteAt;
    
    private static UnitType strategy = UnitType.Ranger; //Our primary attacking unit
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
        
        runPlanet();
    }
    
    private static void runPlanet() {  	
        while (true) {
        	try {
        		long now = System.currentTimeMillis();
        		currentRound = gc.round();
        		debug(1, "Time left at start of round " + currentRound + " = " + gc.getTimeLeftMs());
       
        		if (gc.getTimeLeftMs() > 500) {	        		
		            updateUnits();
		            updateKarbonite();
		            updateResearch();
		            
		            VecUnit known = units.allUnits();
		            for (int i=0; i<known.size(); i++) {
		            	Unit u = known.get(i);
		            	if (u.team() == myTeam)
		            		processUnit(u);
		            }
        		}   
	            
        		debug(1, "Round " + currentRound + " took " + (System.currentTimeMillis() - now) + " ms");
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
    }
 
    private static void debug(int level, String s) {
    	if (level <= debugLevel)
    		System.out.println(s);
    }
    
	/*
	 * Work out our build priorities
	 * We aim to have enough rockets for everyone on earth
	 * We save up for factories if we have 8 workers but less than 2 factories
	 */
    private static void updateBuildPriorities() {
		int unitsToTransport = (myLandUnits[UnitType.Worker.ordinal()] + 
								myLandUnits[UnitType.Healer.ordinal()]) +
								myLandUnits[UnitType.Ranger.ordinal()] +
								myLandUnits[UnitType.Mage.ordinal()] +
								myLandUnits[UnitType.Knight.ordinal()];
		int capacity = (gc.researchInfo().getLevel(UnitType.Rocket) == 3)?12:8;
		int rocketsNeeded = ((unitsToTransport+capacity-1) / capacity) - myLandUnits[UnitType.Rocket.ordinal()];
		
		//We save for rockets in 3 situations
		//1. We have rocket tech and need more rockets
		//2. We don't have rocket tech but have conquered earth
		//3. We don't have rocket tech but have > 250 units
		if (gc.researchInfo().getLevel(UnitType.Rocket) > 0)
			saveForRocket = (myLandUnits[UnitType.Worker.ordinal()] > 0 && rocketsNeeded > 0);
		else
			saveForRocket = (myLandUnits[UnitType.Worker.ordinal()] > 0 && (unitsToTransport > 250 ||
					conquered)  && rocketsNeeded * bc.bcUnitTypeBlueprintCost(UnitType.Rocket) > gc.karbonite());		
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
    	if (max == 10 && info[cx][cy].within10 != null) {
    		return info[cx][cy].within10;
    	}
    	
    	int width = (int) map.getWidth(), height = (int) map.getHeight();
    	
    	if (min < 0)
    		result.add(centre);
    	
    	//The tiles in the resultant circle will be 4 way symmetric along the diagonals and vertices
    	//and 8 way symmetric in other areas

    	//First the horizontal/vertical
    	for (int x=1; x*x<=max; x++) {
    		if (x*x <= min)
    			continue;
    		if (cx+x < width)
				result.add(info[cx+x][cy].here);
			if (cy+x < height)
				result.add(info[cx][cy+x].here);
			if (cx-x >= 0)
				result.add(info[cx-x][cy].here);
			if (cy-x >= 0)
				result.add(info[cx][cy-x].here);
    	}
    	
    	//Now the diagonals
    	for (int x=1; 2*x*x<=max; x++) {
    		if (2*x*x <= min)
    			continue;
    		if (cx+x < width) {
    			if (cy+x < height)
    				result.add(info[cx+x][cy+x].here);
    			if (cy-x >= 0)
    				result.add(info[cx+x][cy-x].here);
    		}
			if (cx-x >= 0) {
				if (cy+x < height)
					result.add(info[cx-x][cy+x].here);
				if (cy-x >= 0)
					result.add(info[cx-x][cy-x].here);
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
						result.add(info[cx+x][cy+y].here);
					if (cy-y >= 0)
						result.add(info[cx+x][cy-y].here);
				}
				if (cx-y >= 0) {
					if (cy+x < height)
						result.add(info[cx-y][cy+x].here);
					if (cy-x >= 0)
						result.add(info[cx-y][cy-x].here);
				}
				if (cx-x >= 0) {
					if (cy-y >= 0)
						result.add(info[cx-x][cy-y].here);
					if (cy+y < height)
						result.add(info[cx-x][cy+y].here);
				}
				if (cx+y < width) {
					if (cy-x >= 0)
						result.add(info[cx+y][cy-x].here);				
					if (cy+x < height)
						result.add(info[cx+y][cy+x].here);
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
    	if (max == 10)
    		info[cx][cy].within10 = result;
    	
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
    private static LinkedList<Unit> senseNearbyUnits(MapLocation centre, long radius, Team team) {
    	LinkedList<Unit> result = new LinkedList<Unit>();
    	
    	for (MapLocation m: allLocationsWithin(centre, -1, radius)) {
    		Unit u = units.unitAt(m);
    		if (u != null && (team == null || u.team() == team)) {
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
     * Returns a value determined by the unit type
     * Higher means we want to shoot this first
     */
    private static int unitPriority(Unit u) {
    	switch (u.unitType()) {
    	case Mage: //Massive damage if they get close - take them out first
    		return 5;
    	case Knight: //Similar to mages - we don't want them getting close
    		return 4;
    	case Healer: //These make ranger battles last for ever by keeping the rangers alive
    		return 3;
    	case Ranger:
    		return 2;  	
    	case Worker:
    		return 1;
    	default: //Buildings
    		return 0;		
    	}
    }
    
    /*
     * splashAttack is called by mages to find the best target to attack
     * For each target we calculate the total damage done to enemy units and our units
     * We may pick to attack our own unit!
     */
    private static Unit splashAttack(Unit unit) {
    	int id = unit.id();
    	
		if (!unit.location().isOnMap() || !gc.isAttackReady(id))
			return unit;
		
    	long mostDamage = 0;
    	Unit best = null;
    	LinkedList<Unit> inRange = senseNearbyUnits(unit.location().mapLocation(), unit.attackRange(), otherTeam);
    	HashSet<Unit> targets = new HashSet<Unit>(); //Unique set of units in splash range
    	
    	for (Unit e: inRange) { //Loop over enemy units and add in all units in splash range
    		targets.addAll(senseNearbyUnits(e.location().mapLocation(), 2, null));
    	}
    	
    	for (Unit centre: targets) {
    		if (gc.canAttack(id, centre.id())) {
    			long totalDamage = 0;
    			for (Unit splashed: senseNearbyUnits(centre.location().mapLocation(), 2, null)) {
	    			long damage = Math.min(splashed.health(), unit.damage()); //How much damage we will do
	    			if (splashed.team() == myTeam)
	    				totalDamage -= damage; //Bad
	    			else
	    				totalDamage += damage; //Good
	    		}
    			
    			if (totalDamage > mostDamage) {    				
	    			best = centre;
	    			mostDamage = totalDamage;
    			}
    		}
    	}

    	if (best == null)
    		return unit;
    	
		debug(2, "Mage firing on " + best.unitType());
		gc.attack(unit.id(), best.id());
		
		//Update all affected units
		for (Unit splashed: senseNearbyUnits(best.location().mapLocation(), 2, null))
			units.updateUnit(splashed.id());
		
		return units.updateUnit(id);
    }
    
    /*
     * attackWeakest
     * 
     * Pick the enemy with the most damage
     * Returns true if we attacked, false if we didn't
     * 
     * We work best if we coordinate attacks on the same round
     */
    private static Unit attackWeakest(Unit unit) {
    	int id = unit.id();
    	
		if (!unit.location().isOnMap() || !gc.isAttackReady(id))
			return unit;
		
		//Pick the enemy with the highest priority and most damage that is in range
		int highestPriority = -1;
    	long mostDamage = -1;
    	Unit best = null;
    	
    	for (Unit enemy: senseNearbyUnits(unit.location().mapLocation(), unit.attackRange(), otherTeam)) {
    		if (gc.canAttack(id, enemy.id())) {
    			int priority = unitPriority(enemy);
    			long damage = enemy.maxHealth() - enemy.health();
    			if (priority > highestPriority || (priority == highestPriority && damage > mostDamage)) {
	    			best = enemy;
	    			highestPriority = priority;
	    			mostDamage = damage;
    			}
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
     * Sniping
     * 
     * Take out structures first (they don't move!)
     * then healers
     * then rangers
     * then other units
     * 
     * If there are no enemies - pick a random passable location that is in the explore zone
     */
    
    LinkedList<MapLocation> snipeTargets = new LinkedList<MapLocation>();
    
    private static MapLocation bestSnipeTarget(LinkedList<Unit> enemies) {
    	LinkedList<MapLocation> enemiesToSnipe = exploreZone;
    	
    	if (enemyStructures.size() > 0)
    		enemiesToSnipe = enemyStructures;
    	else if (enemyHealers.size() > 0)
    		enemiesToSnipe = enemyHealers;
    	else if (enemyRangers.size() > 0)
    		enemiesToSnipe = enemyRangers;
    	else if (enemyOthers.size() > 0)
    		enemiesToSnipe = enemyOthers;
    	  	
    	if (enemiesToSnipe.size() > 0) {
    		int r = randomness.nextInt(enemiesToSnipe.size());
    		return enemiesToSnipe.get(r);
    	}
    	
    	return null;
    }
    
    private static int bestJavelinTarget(Unit knight) {
    	int best_id = -1;
    	int mostDamage = -1;
    	
    	for (Unit enemy: senseNearbyUnits(knight.location().mapLocation(), knight.attackRange(), otherTeam)) {
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
    private static double[][] damagedMap = null; // Used by damaged units - head to a healer
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
    	    if (!processed[x][y] && info[x][y].passable)
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

    			//Score this tile
        		if (gravityMap != null)
        			gravityMap[x][y] += gravity;
        		else { //Rockets
        			for (double[][] m:allMaps) //Add to all maps but ignore workers before round 700
	        			if (m != workerMap || currentRound > 700)
	        				m[x][y] += gravity;
        		}
        		
        		Unit unit = units.unitAt(me);
        		boolean addNeighbours = true;
    			
				if (unit != null && unit.team() == myTeam &&
						(match == null || match == unit.unitType())) {
					matchCount++;
					if (matchCount >= max) { //We have reached the cutoff point
						debug(3, "Ripple match count met: complete at distance " + distance);
						return;
					}
					if (distance == 1 && match != null) { //This is a starting tile that is already occupied by the right unit
						addNeighbours = false;
					}
				}  			
	       		
    			//We add adjacent tiles to the next search if they are traversable
        		if (addNeighbours) {
					for (MapLocation t:info[me.getX()][me.getY()].passableNeighbours) {
		    			if (!processed[t.getX()][t.getY()]) {
			    			nextEdge.add(t);
			    			processed[t.getX()][t.getY()] = true;
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
		
    	for (int x=0; x<map.getWidth(); x++) {
    		for (int y=0; y<map.getHeight(); y++) {
    			double noise = randomness.nextDouble() / 10000.0;
    			Unit u = units.unitAt(info[x][y].here);
    			if (u != null && u.team() == myTeam && u.unitType() == UnitType.Factory)
    				noise = 0; //Don't randomly walk into factories
    			for (double[][] me:allMaps) {
	    			me[x][y] = noise;    			
	    			if (me != knightMap) //Knights ignore danger zones
	    				me[x][y] -= danger[x][y];
		    	}
	    	}
    	}
		
		/*
		 * The damagedMap is for all units who have lost half their health
		 */
    	if (unitsToHeal.size() > 0 && healers.size() > 0)
    		ripple(damagedMap, healers, 50, null, 1000);
    	
    	/*
    	 * Add Rockets that are ready to board and have space
    	 * We only broadcast when we have spare units (more combat units than map w+h)
    	 * Or it is near the flood (turn 600 onwards)
    	 */
    	int totalCombatForce = myLandUnits[UnitType.Knight.ordinal()] +
    							myLandUnits[UnitType.Ranger.ordinal()] +
    							myLandUnits[UnitType.Mage.ordinal()];
    	int desiredUnits = (int)(map.getWidth() + map.getHeight()) / 2;
    	if (conquered)
    		desiredUnits = 0;
    	int passengers = totalCombatForce - desiredUnits;
    	
    	if (myPlanet == Planet.Earth) {
	    	if (conquered || currentRound > EvacuationRound) {
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

    	//If no enemies - explore
    	if (enemies.size() == 0) {
    		LinkedList<MapLocation> explore = (currentRound < 200 && enemyLocs.size() > 0)?enemyLocs:exploreZone;
	    	ripple(rangerMap, explore, 30, UnitType.Ranger, rangerCount);
    	}
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
    	
    	//Avoid all enemies
    	LinkedList<MapLocation> targets = new LinkedList<MapLocation>();
    	for (Unit u:enemies)
    		targets.add(u.location().mapLocation()); 
    	ripple(healerMap, targets, -10, UnitType.Healer, healerCount);
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
    	
    	//If no enemies - explore
    	if (enemies.size() == 0) {
    		LinkedList<MapLocation> explore = (currentRound < 200 && enemyLocs.size() > 0)?enemyLocs:exploreZone;
    		ripple(knightMap, explore, 30, UnitType.Knight, knightCount);
    	}
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
		
		//Add blueprints and damaged buildings - this is usually a small list so do them individually
		for (MapLocation m: unitsToBuild) {
			LinkedList<MapLocation> workSpace = info[m.getX()][m.getY()].passableNeighbours;
			ripple(workerMap, workSpace, 200, UnitType.Worker, Math.min(workerCount, workSpace.size()));
		}
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
    	damagedMap = new double[w][h];
        allMaps = new double[][][] { mageMap, rangerMap, workerMap, knightMap, healerMap, damagedMap };
    	
        /*
         * Create and cache a MapLocation for each location on the map and whether it is passable and has karbonite
         */
        karboniteAt = new long[w][h];
        for (int x = 0; x<map.getWidth(); x++) {
    		for (int y=0; y<map.getHeight(); y++) {
    			MapLocation m = new MapLocation(myPlanet, x, y);
    			info[x][y] = new MapInfo(m, (map.isPassableTerrainAt(m) > 0));
    			karboniteAt[x][y] = map.initialKarboniteAt(m);
    			if (karboniteAt[x][y] > 0) {
    				karboniteLocation.add(m);
    				turnsToMine += (karboniteAt[x][y] + 2) / 3;
    			}   
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
    	
    	int minWorkers = (turnsToMine == 0?4:8);
    	maxWorkers = Math.max(minWorkers, turnsToMine / 100);
    	
    	debug(1, "We need " + maxWorkers + " workers on " + myPlanet);

    	if (myPlanet == Planet.Earth) {
        	if (maxWorkers > 8)
            	gc.queueResearch(UnitType.Worker); // Increase harvest amount
        	
    		earth = new MapAnalyser(gc, gc.startingMap(Planet.Earth), info);
    		mars = new MapAnalyser(gc, gc.startingMap(Planet.Mars), null);
   		
    		/*
    		 * Work out if we are in the same zone as an opponent
    		 * If not we can build units best suited for mars
    		 */
    		boolean separated = false; //Set to true if we start in a zone where the enemy isn't
    		VecUnit start = map.getInitial_units();
    		HashSet<Integer> myZones = new HashSet<Integer>();
    		HashSet<Integer> enemyZones = new HashSet<Integer>();
    		
    		for (int i=0; i<start.size(); i++) {
    			Unit u = start.get(i);
    			MapLocation where = u.location().mapLocation();
    			if (u.team() == myTeam)
    				myZones.add(info[where.getX()][where.getY()].zone);
    			else {
    				enemyZones.add(info[where.getX()][where.getY()].zone);
    				enemyLocs.add(where);
    			}
    		}
    		
    		//Check to see if we are in any zones that the enemy isn't
    		for (int zone: myZones)
    			if (!enemyZones.contains(zone))
    				separated = true;
        	
        	debug(1, "Earth has " + earth.zones.size() + " zones, separated = " + separated);
        	
        	if (separated) {
        		//Get to mars quickly
        		gc.queueResearch(UnitType.Rocket);
        	} else if (Math.max(map.getWidth(), map.getHeight()) <= 30) // We are on a small map and connected
        		strategy = UnitType.Mage;
    	}
    }   
	
	/*
	 * Find the best location to build
	 * We want safe open space around us if possible and not adjacent to other structures
	 */
	private static MapLocation bestBuildLocation(MapLocation loc) {
		LinkedList<MapLocation> options = allOpenNeighbours(loc);
		MapLocation result = null;
		
		if (!options.isEmpty()) {
			//Score each option.
			int bestScore = (400 - Math.max(400, (int)gc.karbonite())) / 50; //We want lots of open neighbours but override this if we have lots of karbonite			
	    	for (MapLocation test: options) {      		
	    		if (danger[test.getX()][test.getY()] == 0) {
	    			int score = 0;
	    			//Count the passable neighbours that don't contain a structure
	    			for (MapLocation m: info[test.getX()][test.getY()].passableNeighbours) {  
	    				Unit u = units.unitAt(m);
	    				if (u == null)
	    					score++;
	    				else if (u.unitType() == UnitType.Factory || u.unitType() == UnitType.Rocket)
	    					score--;
	    				else
	    					score++;
	    			}
	    			if (karboniteAt[test.getX()][test.getY()] > 0)
	    				score--;
	    			
	    			if (score > bestScore) {
	    				bestScore = score;
	    				result = test;
	    			}
	    		}
	    	}
		}  
		return result;
	}
    
	/*
	 * Returns the location score from the given gravity map
	 * If the unit type is a ranger then we ignore the danger component of the score some of the time
	 */
	private static double locationScore(double[][] gravityMap, int x, int y, Unit u) {
		if (u.unitType() == UnitType.Ranger && currentRound % 20 < 3 && danger[x][y] < u.health()) {
			return gravityMap[x][y] + danger[x][y];
		}
		return gravityMap[x][y];
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
    	
    	if (t.health() * 2 < t.maxHealth() && healers.size() > 0 && t.unitType() != UnitType.Healer) //We've lost more than half our health
    		gravityMap = damagedMap;
  	
    	MapLocation myLoc = t.location().mapLocation();
    	boolean isStructure =  (t.unitType() == UnitType.Factory || t.unitType() == UnitType.Rocket);   	
    	double bestScore = (move?-100000:locationScore(gravityMap, myLoc.getX(), myLoc.getY(), t));
    	LinkedList<MapLocation> options = null;
    	
    	debug(4, "bestMove from " + myLoc + " current score " + bestScore);
		if (isStructure) //We are looking to unload from here (as a structure can't move!)
			options = allOpenNeighbours(myLoc);
		else
			options = allMoveNeighbours(myLoc);
    	for (MapLocation test: options) {
    		Direction d = myLoc.directionTo(test);
    		double score = locationScore(gravityMap, test.getX(), test.getY(), t);
    		if (score > bestScore) {
    			bestScore = score;
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
    			long k = gc.karboniteAt(m);
    			if (k == 0)
    				iterator.remove();
    			karboniteAt[m.getX()][m.getY()] = k;			
    		}
    	}	
    	
    	if (myPlanet == Planet.Mars) {
    		if (gc.asteroidPattern().hasAsteroid(currentRound)) {
    			MapLocation strike = gc.asteroidPattern().asteroid(currentRound).getLocation();
    			karboniteLocation.add(strike);
    			karboniteAt[strike.getX()][strike.getY()] = gc.asteroidPattern().asteroid(currentRound).getKarbonite();
    		}
    	}
    }
    
    /*
     * Decide what to research next based on the current strategy and game position
     */
    private static void updateResearch() {
    	ResearchInfo ri = gc.researchInfo();
    	
    	if (myPlanet != Planet.Earth || ri.hasNextInQueue())
    		return;
    	
    	if ((conquered || currentRound > 200) && ri.getLevel(UnitType.Rocket) == 0) { //Time for rockets
    		gc.queueResearch(UnitType.Rocket);
    	} else if (ri.getLevel(strategy) == 0 && strategy != UnitType.Knight) { //We get the level 1 for our preferred unit first
    		gc.queueResearch(strategy);
    	} else if (ri.getLevel(UnitType.Healer) < 2) { //We upgrade Healers twice before finishing off our preferred units
    		gc.queueResearch(UnitType.Healer);
    	} else if (ri.getLevel(strategy) < 3) { // Complete our strategy unit
    		gc.queueResearch(strategy);
    	} else if (ri.getLevel(UnitType.Healer) < 3) { // Overcharge
    		gc.queueResearch(UnitType.Healer);
    	} else if (ri.getLevel(UnitType.Mage) < 4) {
    		gc.queueResearch(UnitType.Mage);
    	}
    }
    
    private static int[] enemyUnits = new int[UnitType.values().length]; //Counts of how many units enemy has indexed by unit type (ordinal)
    private static int[] myLandUnits = new int[UnitType.values().length]; //Counts of how many units we have indexed by unit type (ordinal)
    private static int[] mySpaceUnits = new int[UnitType.values().length]; //Counts of how many units we have indexed by unit type (ordinal)
    private static LinkedList<MapLocation> unitsToBuild = new LinkedList<MapLocation>(); //List of current blueprints that need building
    private static LinkedList<MapLocation> unitsToHeal = new LinkedList<MapLocation>(); //List of units that need healing
    private static LinkedList<MapLocation> healers = new LinkedList<MapLocation>(); //Location of our healers - we head to here when damaged
    private static LinkedList<Unit> rockets = new LinkedList<Unit>(); //List of rockets (to Load into if on Earth, or unload from on Mars)
    private static LinkedList<Unit> enemies = new LinkedList<Unit>(); //List of all enemy units in sight
    private static LinkedList<MapLocation> enemyStructures = new LinkedList<MapLocation>();
    private static LinkedList<MapLocation> enemyHealers = new LinkedList<MapLocation>();
    private static LinkedList<MapLocation> enemyRangers = new LinkedList<MapLocation>();
    private static LinkedList<MapLocation> enemyOthers = new LinkedList<MapLocation>();
    private static int[][] danger; //Array (x,y) of map locations and how much damage a unit would take there
    private static boolean[][] visible; //Array (x,y) of map locations: true we can see (sense) it
    private static boolean saveForRocket = false;
    private static LinkedList<MapLocation> exploreZone = new LinkedList<MapLocation>(); //All locs that are passable and not visible but next to a visible location
    private static LinkedList<MapLocation> enemyLocs = new LinkedList<MapLocation>(); //Start position of the enemy - used in place of the exploreZone at the start of the game
    private static boolean conquered = false; //Set to true on Earth if we can see all the map and no enemies
    
    /*
     * Loop through the units we are aware of and update our cache
     * Called once each turn
     */
    private static void updateUnits() {
        units.updateCache(); //All the units we can see
        unitsInSpace = gc.unitsInSpace(); //All the units in space
        Arrays.fill(enemyUnits, 0);
    	Arrays.fill(myLandUnits, 0);
    	Arrays.fill(mySpaceUnits, 0);
    	unitsToBuild.clear();
    	unitsToHeal.clear();
    	healers.clear();
    	rockets.clear();
    	enemies.clear();
    	enemyStructures.clear();
    	enemyHealers.clear();
    	enemyRangers.clear();
    	enemyOthers.clear();
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
            		if (unit.visionRange() == 2) {
            			within = info[here.getX()][here.getY()].neighbours;
            			visible[here.getX()][here.getY()] = true;
            		} else
            			within = allLocationsWithin(here, -1, unit.visionRange());
            		for (MapLocation m: within) {
        				int x = m.getX(), y = m.getY();
        				visible[x][y] = true;	
        			}
            		
            		if (unit.unitType().equals(UnitType.Factory) || unit.unitType().equals(UnitType.Rocket)) {
            			if (unit.structureIsBuilt() == 0 || unit.health() < unit.maxHealth())
            				unitsToBuild.add(here);
	
            			if (unit.structureIsBuilt() > 0 && unit.unitType().equals(UnitType.Rocket) && unit.rocketIsUsed() == 0)
            				rockets.add(unit);
            			
            			VecUnitID garrison = unit.structureGarrison();
            			for (int j=0; j<garrison.size(); j++) {
            				int id = garrison.get(j);
            				myLandUnits[gc.unit(id).unitType().ordinal()]++;
            			}
            		} else {
                		if (unit.unitType() == UnitType.Healer)
                			healers.add(unit.location().mapLocation());
            			if (unit.health() < unit.maxHealth())
            				unitsToHeal.add(unit.location().mapLocation());
            		}
            	} else { //enemies
            		enemies.add(unit);
            		enemyUnits[unit.unitType().ordinal()]++;

            		switch (unit.unitType()) {
	            		case Factory:
	            			enemyStructures.add(unit.location().mapLocation());
	            			break;
	            		case Ranger:
	            			enemyRangers.add(unit.location().mapLocation());
	            			for (MapLocation m:allLocationsWithin(unit.location().mapLocation(), unit.rangerCannotAttackRange(), unit.attackRange())) {
	            				int x = m.getX(), y = m.getY();
	            				danger[x][y] += unit.damage();	
	            			}
	            			break;
	            		case Knight: //Increase radius to 10 to account for them moving then attacking
	            			enemyOthers.add(unit.location().mapLocation());
	            			for (MapLocation m:allLocationsWithin(unit.location().mapLocation(), -1, 10)) {
	            				int x = m.getX(), y = m.getY();
	            				danger[x][y] += unit.damage()/2;
	            			}
	            			for (MapLocation m:info[unit.location().mapLocation().getX()][unit.location().mapLocation().getY()].passableNeighbours) {
	            				int x = m.getX(), y = m.getY();
	            				danger[x][y] += unit.damage()/2;
	            			}
	            			break;
	            		case Mage: //TODO - Increase radius to account for splash damage
	            			enemyOthers.add(unit.location().mapLocation());
	            			for (MapLocation m:allLocationsWithin(unit.location().mapLocation(), -1, unit.attackRange())) {
	            				int x = m.getX(), y = m.getY();
	            				danger[x][y] += unit.damage();
	            			}
	            			break;
	            		case Rocket: //These damage neighbours when they take off (so only dangerous on Earth)
	            			enemyStructures.add(unit.location().mapLocation());
	            			if (myPlanet == Planet.Earth && unit.structureIsBuilt() > 0) { 
	            				for (MapLocation m:info[unit.location().mapLocation().getX()][unit.location().mapLocation().getY()].passableNeighbours) {
		            				int x = m.getX(), y = m.getY();
		            				danger[x][y] += 100;
		            			}
	            			}
	            			break;
	            		case Healer:
	            			enemyHealers.add(unit.location().mapLocation());
	            			break;
	            		default:
	            			enemyOthers.add(unit.location().mapLocation());
	            			break;
            		}
            	}
            }
    	}
    	
    	/*
    	 * Adjust our strategy according to enemies seen
    	 */
		if (currentRound >= EvacuationRound)
			strategy = UnitType.Mage;
		else if (currentRound >= 150)
    		strategy = UnitType.Ranger;
    	else if (strategy != UnitType.Ranger) {
    		if (strategy == UnitType.Mage && enemyUnits[UnitType.Ranger.ordinal()] > 0) //Rangers beat Mages
    			strategy = UnitType.Knight;
    		if (strategy == UnitType.Knight && enemyUnits[UnitType.Mage.ordinal()] > 0) //Mages beat Knights
    			strategy = UnitType.Ranger;
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
    	 * If we have seen the enemy starting location remove it from the list as we no longer want to explore it
    	 */
    	for (Iterator<MapLocation> iterator = enemyLocs.iterator(); iterator.hasNext();) {
    	    MapLocation m = iterator.next();
    	    int x = m.getX(), y = m.getY();
    	    if (visible[x][y])
    			iterator.remove();
    	}
    	
    	/*
    	 * Produce the list of locations that are unseen but next to a location we can see
    	 * i.e. the border of known space
    	 * If we have no units this list is empty
    	 */
    	if (!conquered && units.allUnits().size() > 0) {
			for (int x=0; x<map.getWidth(); x++) {
				for (int y=0; y<map.getHeight(); y++) {  
					if (!visible[x][y] && info[x][y].passable) { //Unseen - are we adjacent to a visible location
						for (MapLocation m:info[x][y].passableNeighbours) {
							if (visible[m.getX()][m.getY()]) {
								exploreZone.add(info[x][y].here);
								break;
							}
						}
					}
				}
			}
    	}
    	
    	if (!conquered && myPlanet == Planet.Earth && exploreZone.size() == 0 && enemies.size() == 0) {
    		conquered = true;
    		debug(0, "Earth is conquered!");
    	}
    	
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
    	
    	updateBuildPriorities();
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
		
		//Can we help build or repair something
    	for (MapLocation m:info[loc.getX()][loc.getY()].passableNeighbours) {			
			Unit other = units.unitAt(m);
			if (other != null && other.team() == myTeam &&
					(other.unitType() == UnitType.Factory || other.unitType() == UnitType.Rocket)) {
				if (gc.canBuild(id, other.id())) {
					gc.build(id, other.id());
					unit = units.updateUnit(id);
					debug(2, "worker building");
				}
				if (other.health() < other.maxHealth() && gc.canRepair(id, other.id())) {
					gc.repair(id, other.id());
					unit = units.updateUnit(id);
  					debug(2, "worker is repairing");
				}
			}
		}

		/*
		 * Now check to see if we want to blueprint a factory or a rocket
		 */		
    	long k = gc.karbonite(); 	
    	Direction dir = bestMove(unit, getGravityMap(unit.unitType()), true);
    	   
		if (unit.workerHasActed() == 0 && myPlanet == Planet.Earth &&
				k >= Math.min(bc.bcUnitTypeBlueprintCost(UnitType.Rocket),
											bc.bcUnitTypeBlueprintCost(UnitType.Factory))) {
	    	MapLocation buildLoc = null;
			
	    	/*
	    	 * Sometimes we block ourselves in due to excessive population (or a small planet)
	    	 * If we want to build a rocket and we have no rockets then we destroy an adjacent unit and build there!
	    	 */
	    	boolean wantRocket = (saveForRocket && myLandUnits[UnitType.Rocket.ordinal()] == 0 &&
	    			k >= bc.bcUnitTypeBlueprintCost(UnitType.Rocket));
	    	boolean wantFactory = (myLandUnits[UnitType.Factory.ordinal()] == 0 &&
	    			k >= bc.bcUnitTypeBlueprintCost(UnitType.Factory));	    	
	    	boolean needSacrifice = (dir == null && (wantRocket || wantFactory));
	    	
	    	if (needSacrifice) {
	    		Unit suicide = null;
	    		for (Unit u: senseNearbyUnits(loc, 2, myTeam)) {
	    			suicide = u;
	    			if (u.unitType() != UnitType.Factory)
	    				break;
	    		}
	    		if (suicide != null) {
	    			debug(2, "Destroying " + suicide.unitType() + " to make room for a new structure");
	    			buildLoc = suicide.location().mapLocation();
	    			units.removeUnit(buildLoc);
	    			gc.disintegrateUnit(suicide.id());
	    		}
	    	}
	    	
	    	if (buildLoc == null)
	    		buildLoc = bestBuildLocation(loc);		
	    	
	    	if (buildLoc != null) {
	    		dir = loc.directionTo(buildLoc);
				if (saveForRocket &&
						k >= bc.bcUnitTypeBlueprintCost(UnitType.Rocket) &&
						gc.canBlueprint(id, UnitType.Rocket, dir)) {
					gc.blueprint(id, UnitType.Rocket, dir);
					units.updateUnit(buildLoc);
					unit = units.updateUnit(id);
					debug(2, "worker blueprinting rocket");
					myLandUnits[UnitType.Rocket.ordinal()]++;
					k = gc.karbonite();
					updateBuildPriorities();
					dir = bestMove(unit, getGravityMap(unit.unitType()), true); //Update as we might replicate
				}
				
				if (!saveForRocket && k >= bc.bcUnitTypeBlueprintCost(UnitType.Factory) &&
						gc.canBlueprint(id, UnitType.Factory, dir)) {
					gc.blueprint(id, UnitType.Factory, dir);
					units.updateUnit(buildLoc);
					unit = units.updateUnit(id);
					debug(2, "worker blueprinting factory");
					myLandUnits[UnitType.Factory.ordinal()]++;
					updateBuildPriorities();
					dir = bestMove(unit, getGravityMap(unit.unitType()), true); //Update as we might replicate
				}
	    	}
		}
		
		//Can we Harvest? Pick the location with the most karbonite
		if (unit.workerHasActed() == 0) {
			long most = karboniteAt[loc.getX()][loc.getY()];
			MapLocation best = loc;
			for (MapLocation h: info[loc.getX()][loc.getY()].neighbours) {
				if (visible[h.getX()][h.getY()] && karboniteAt[h.getX()][h.getY()] > most) {
					most = karboniteAt[h.getX()][h.getY()];
					best = h;
				}
			}
			
			if (most > 0) {
				Direction d = loc.directionTo(best);
				if (gc.canHarvest(id, d)) {
					gc.harvest(id, d);
					unit = units.updateUnit(id);
					debug(2, "worker harvesting");
					karboniteAt[best.getX()][best.getY()] -= unit.workerHarvestAmount();
					if (karboniteAt[best.getX()][best.getY()] <= 0) {
						karboniteAt[best.getX()][best.getY()] = 0;
						//We don't need to remove this location from the KarboniteLocation list here as it is used
						//at the start of the turn and will be correctly updated next turn
					}
				}
			}
		}
		
		//Check to see if we should replicate
    	boolean replicate = (!saveForRocket && myLandUnits[UnitType.Worker.ordinal()] < maxWorkers);
    	if (karboniteLocation.size() == 0 && myLandUnits[UnitType.Factory.ordinal()] == 0)
    		replicate = false;
    	if (myPlanet == Planet.Mars && LastRound - currentRound < 50) //Might as well spend all our karbonite
    			replicate = true;
    
		//We can replicate even if we have acted
    	if (dir != null && replicate && gc.canReplicate(id, dir)) {
    		gc.replicate(id, dir);
    		debug(2, "worker replicating");
    		unit = units.updateUnit(id);
    		myLandUnits[UnitType.Worker.ordinal()]++;
    		Unit newWorker = units.updateUnit(loc.add(dir));
    		processUnit(newWorker);
    	}
    }
    
    /***********************************************************************************
     * Handlers for managing launch and destinations
     * We head off if
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
		int startZone = marsZone;
		do {
	    	marsZone++;
	    	if (marsZone >= mars.zones.size())
	    		marsZone = 0;
	    	if (mars.zones.get(marsZone).landingSites.size() > 1)
	    		return; //We found a zone with room
		} while (marsZone != startZone);
		
		//Try again but allow zones of size 1
		do {
	    	marsZone++;
	    	if (marsZone >= mars.zones.size())
	    		marsZone = 0;
	    	if (mars.zones.get(marsZone).landingSites.size() > 0)
	    		return; //We found a zone with room
		} while (marsZone != startZone);
	}
	
    /*
     * Pick a random location in the current zone
     * Valid landing sites are removed from the zone once a rocket has taken off
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
     * Rockets leave Earth when full and then
     * unload all their occupants as fast as possible on Mars
     * 
     * Ideally we wait for a few rounds once full to give all units inside time to cooldown
     * and signal that the area around us is dangerous when we take off - TODO
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
    						unit = units.updateUnit(id);
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
    	if (garrisoned > 0) {
    		/*
    		 * Pick the direction our strategy unit would move
    		 */
			while (garrisoned > 0) {
    			Direction dir = bestMove(unit, getGravityMap(strategy), true);
    			if (dir != null && gc.canUnload(unit.id(), dir)) {
    				gc.unload(unit.id(), dir);
    				debug(2, "Unloading from factory");
    				MapLocation where = unit.location().mapLocation().add(dir);	        			
    	    		processUnit(units.updateUnit(where));
    			}
    			garrisoned--;
			}
			unit = units.updateUnit(unit.id()); //Update garrison info
    	}
    	
    	/*
    	 * Produce units
    	 * 
    	 * The number of healers we need is determined by the state of play.
    	 * The algorithm is adaptive, i.e. we check to see how many units need healing and create healers accordingly
    	 * We have an upper bound equal to the number of our strategy unit and a miniumum of 1/4 of that
    	 */   		
    	UnitType produce = strategy;
    	int combatUnits = myLandUnits[UnitType.Ranger.ordinal()] + myLandUnits[UnitType.Mage.ordinal()] + myLandUnits[UnitType.Knight.ordinal()];
    	int healers = unitsToHeal.size()*2;
    	if (healers > combatUnits / 2)
    		healers = combatUnits / 2;
    	else if (healers < combatUnits / 4)
    		healers = combatUnits / 4;
    	
    	if (myLandUnits[UnitType.Worker.ordinal()] < Math.min(maxWorkers, combatUnits))
    		produce = UnitType.Worker;
    	else if (myLandUnits[UnitType.Healer.ordinal()] < healers)
    		produce = UnitType.Healer;
    	else if ((myLandUnits[UnitType.Ranger.ordinal()] + 1)*2 < myLandUnits[UnitType.Mage.ordinal()])
    		produce = UnitType.Ranger; //Mages need the vision range of rangers
    	
    	if ((!saveForRocket || produce == UnitType.Worker) && gc.canProduceRobot(fid, produce)) {
			gc.produceRobot(fid, produce);
			debug(2, "Factory starts producing a " + produce);
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
    		for (MapLocation o:allLocationsWithin(here, -1, unit.abilityRange())) {
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
    	
    	unit = splashAttack(unit);	   	  	
        unit = moveUnit(unit);
        if (unit.location().isOnMap())
    		splashAttack(unit);
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
    			unit = units.updateUnit(unit.id());
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
        		unit = units.updateUnit(unit.id());
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
    		//Pick the unit with the most damage in range
    		long mostDamage = -1;
    		Unit unitToHeal = null;
	    	for (Unit u: senseNearbyUnits(unit.location().mapLocation(), unit.attackRange(), myTeam)) {
	    		long damage = u.maxHealth() - u.health();
	    		if (damage > mostDamage && gc.canHeal(unit.id(), u.id())) {
	    			mostDamage = damage;
	    			unitToHeal = u;
	    		}
	    	}
	    	if (unitToHeal != null) {
		    	gc.heal(unit.id(), unitToHeal.id());
				units.updateUnit(unitToHeal.id());
				unit = units.updateUnit(unit.id());
				debug(2, "Healing " + unitToHeal.unitType() + " @ " + unitToHeal.location().mapLocation());
	    	}
    	}
    	
    	if (gc.isOverchargeReady(unit.id())) {
    		long mostHeat = 90; //No point wasting our ability for a small gain
    		Unit bestTarget = null;
	    	for (Unit u:senseNearbyUnits(unit.location().mapLocation(), unit.abilityRange(), myTeam)) {
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
				units.updateUnit(bestTarget.id());
				unit = units.updateUnit(unit.id());
				debug(2, "Overcharging " + bestTarget.unitType() + " @ " + bestTarget.location().mapLocation());
				processUnit(bestTarget);
	    	}
    	}
    }
}