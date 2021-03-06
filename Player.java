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
    private static MapAnalyser mars;
    private static int zones; //The number of distinct zones on our planet
    private static ZoneState[] zoneState; //Information on each zone on our planet
    private static MapState mapState;
    
    private static long currentRound; //Updated each turn
    private static UnitCache units; //The list of all known units - updated each turn in the main loop   
    private static VecUnit unitsInSpace; //The list of all units on the way to mars
    private static Random randomness = new Random(74921);
    private static Karbonite karbonite;
    private static boolean conquered = false; //Set to true once we have conquered earth
    private static boolean haltProduction = false; //Set to true when we need to save up for a rocket or we have too many units to process
 
    private static final long EvacuationRound = 600;
    private static final long FloodRound = 749;
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
        units = new UnitCache(gc);
        
    	scanMap();      
        runPlanet();
    }
    
    private static void runPlanet() {  	
        while (true) {
        	try {
        		//long now = System.currentTimeMillis();
        		currentRound = gc.round();
        		//debug(1, "Time left at start of round " + currentRound + " = " + gc.getTimeLeftMs());
       
        		if (gc.getTimeLeftMs() > 500) {	        		
		            updateUnits();
		            karbonite.update(mapState);
		            updateResearch();
		            
		            VecUnit known = units.allUnits();
		            for (int i=0; i<known.size(); i++) {
		            	Unit u = known.get(i);
		            	if (u.team() == myTeam)
		            		processUnit(u);
		            }
        		}   
	            
        		//debug(1, "Round " + currentRound + " took " + (System.currentTimeMillis() - now) + " ms");
        	} catch (Exception e) {
        		//Ignore
        		System.out.println("Caught exception " + e);
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
 
    /*
    private static void debug(int level, String s) {
    	if (level <= debugLevel)
    		System.out.println(s);
    }
    */
    
	/*
	 * Work out our build priorities
	 * We aim to have enough rockets for everyone on earth - each zone is calculated separately
	 * If any zone needs a rocket we halt production if required until we have enough karbonite
	 * If we have the karbonite to build a structure, work out the best location next to a worker and ripple from it's neighbours
	 */
    private static void updateBuildStrategy() {
    	if (myPlanet == Planet.Mars) { //We don't build on Mars
    		return;
    	}
    	
		int totalRocketsNeeded = 0;
		long k = gc.karbonite();		
		haltProduction = (units.allUnits().size() > 500);
		
    	for (int z=0; z < zones; z++)
    		totalRocketsNeeded += zoneState[z].rocketsNeeded(currentRound);	
    	
    	if (gc.researchInfo().getLevel(UnitType.Rocket) > 0) //We have the tech - save up
    		haltProduction |= (totalRocketsNeeded * bc.bcUnitTypeBlueprintCost(UnitType.Rocket) > k);
    	else //We don't have the tech yet - only save up if we have conquered Earth
    		haltProduction |= conquered;
    	
    	if (haltProduction)
    		return;
    	
    	if (gc.researchInfo().getLevel(UnitType.Rocket) > 0)
    		k -= totalRocketsNeeded*bc.bcUnitTypeBlueprintCost(UnitType.Rocket); //We need to spend this much on rockets
    	
    	if (k < bc.bcUnitTypeBlueprintCost(UnitType.Factory))
    		return;
    	
    	/*
		 * We ensure the zone with the least factories builds first
		 */
		int minFactories = 10000;
		int minZone = -1;
		for (int z=0; z<zones; z++) {
			if (zoneState[z].myLandUnits[UnitType.Worker.ordinal()] > 0 && zoneState[z].myLandUnits[UnitType.Factory.ordinal()] < minFactories) {
				minFactories = zoneState[z].myLandUnits[UnitType.Factory.ordinal()];
				minZone = z;
			}
		}
		
		int threshold = (400 - Math.min(400, (int)gc.karbonite())) / 20; //We want lots of open neighbours but override this if we have lots of karbonite			
    	
		Unit bestWorker = null; //best worker to build
		Direction dir = null; //Direction for best worker to build in
		int bestScore = threshold; //Points for space, workers and lack of enemies
		
		Unit bestWorkerWithSacrifice = null;
		Direction dirWithSacrifice = null;
		int bestScoreWithSacrifice = threshold;

		for (Unit w: workers) {
			MapLocation loc = w.location().mapLocation();
			if (map.zone(loc) == minZone) {					
				int baseScore = 0;
				for (Unit u:senseNearbyUnits(loc, 50, null)) {
					if (u.team() == myTeam) {
						if (u.unitType().equals(UnitType.Worker))
							baseScore++;
					} else {
						switch (u.unitType()) {
						case Knight:
						case Ranger:
						case Mage:
							baseScore-=2;
							break;
						case Factory:
							baseScore-=4;
							break;
						default:
							break;
						}
					}
				}
		    	
		    	LinkedList<MapLocation> options = allOpenNeighbours(loc);
		    	boolean sacrifice = (options.size() == 0);
		    	if (sacrifice) //We'd need a sacrifice to build from here
		    		options = map.passableNeighbours(loc);
				
				for (MapLocation m: options) {
					if (mapState.danger(m.getX(), m.getY()) == 0) {
						int score = baseScore;
						for (MapLocation n:map.passableNeighbours(m)) {
							score++; //Each open neighbour is good for getting workers here
							Unit u = units.unitAt(n);
							if (u != null && u.unitType().equals(UnitType.Factory))
								score-=4;
						}
		    			if (karbonite.karboniteAt(m) > 0)
		    				score--;

		    			if (sacrifice) {
		    				if (score > bestScoreWithSacrifice) {
			    				bestScoreWithSacrifice = score;
			    				dirWithSacrifice = loc.directionTo(m);
			    				bestWorkerWithSacrifice = w;
		    				}
		    			} else if (score > bestScore) {
		    				bestScore = score;
		    				dir = loc.directionTo(m);
		    				bestWorker = w;
		    			}
		    		}
				}
			}
		}
		
		if (bestWorker == null && bestWorkerWithSacrifice != null && zoneState[minZone].myLandUnits[UnitType.Factory.ordinal()] == 0) {
			MapLocation buildLoc = bestWorkerWithSacrifice.location().mapLocation().add(dirWithSacrifice);
			Unit s = units.unitAt(buildLoc);
			gc.disintegrateUnit(s.id());
			bestWorker = bestWorkerWithSacrifice;
			dir = dirWithSacrifice;
		}
		
		if (bestWorker != null && gc.canBlueprint(bestWorker.id(), UnitType.Factory, dir)) {
			MapLocation m = bestWorker.location().mapLocation().add(dir);
			gc.blueprint(bestWorker.id(), UnitType.Factory, dir);
			units.updateUnit(m);
			units.updateUnit(bestWorker.id());
			//debug(2, "worker blueprinting factory");
			zoneState[minZone].myLandUnits[UnitType.Factory.ordinal()]++;
			
			LinkedList<MapLocation> workSpace = new LinkedList<MapLocation>();
			
			for (MapLocation p: map.passableNeighbours(m)) {
				Unit u = units.unitAt(p);
				if (u == null || (!u.unitType().equals(UnitType.Factory) && !u.unitType().equals(UnitType.Rocket)))
					workSpace.add(p);
			}

			ripple(workerMap, workSpace, 20, UnitType.Worker, workSpace.size(), -1);
		}
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
    	
    	for (MapLocation test:map.passableNeighbours(l)) {
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
    	for (MapLocation test:map.passableNeighbours(l)) {
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
    	
    	for (MapLocation m: map.allLocationsWithin(centre, -1, radius)) {
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
    private static Unit moveUnit(Unit unit, boolean allowStructure) {
    	int id = unit.id();
    	
    	if (!unit.location().isOnMap() || !gc.isMoveReady(id))
    		return unit;
    	
    	Direction d = bestMove(unit, getGravityMap(unit.unitType()), false);  
    	if (d == null)
    		return unit; //Nowhere better
    	
    	MapLocation loc = unit.location().mapLocation();
		MapLocation dest = loc.add(d);
		
		if (gc.canMove(id, d)) {
			units.removeUnit(loc);
			gc.moveRobot(id, d);  			
			unit = units.updateUnit(id);
		} else { //Check to see if there is a structure of ours there
			Unit structure = units.unitAt(dest);
			if (structure != null && allowStructure && gc.canLoad(structure.id(), id)) {
				units.removeUnit(loc);
				gc.load(structure.id(), id);
				units.updateUnit(structure.id());
				unit = gc.unit(unit.id());
				//debug(2, "Loading " + unit.unitType() + " into " + structure.unitType());
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
    	
		//debug(2, "Mage firing on " + best.unitType());
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
    	
		//debug(2, unit.unitType() + " firing on " + best.unitType());
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
    	LinkedList<MapLocation> enemiesToSnipe = mapState.exploreZone();
    	
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
    
    private static MapCache map = null;
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
    
    private static boolean isMatch(MapLocation here, UnitType match, boolean ignoreWorkers) {
    	Unit unit = units.unitAt(here);
		return (unit != null && unit.team() == myTeam &&
				(match == null || match == unit.unitType()) &&
				(unit.unitType() != UnitType.Worker || !ignoreWorkers));
    }
    
    /*
     * Ripple out from the given edge (set of points) until a given number of our units have been found scoring each tile as we go - the nearer the higher the score
     * For each location we mark it as open (i.e. on the list to process), closed (processed) or unseen
     * 
     * Since this routine is called more than any other - efficiency is key.
     * If called with a null gravity map then we actually add to all gravity maps
     */
    public static void ripple(double[][] gravityMap, LinkedList<MapLocation> edge, double points, UnitType match, int max, int stop) {
    	boolean[][] open = new boolean[map.width()][map.height()]; //true if in the open list
    	int distance = 0; //How far from the source are we
    	int matchCount = 0; //How many units of the right type have we seen
    	boolean ignoreWorkers = (gravityMap == null && currentRound <= 700); //Rockets don't want workers before round 700
    	
    	//debug(3, "ripple: starting points " + edge.size() + " value " + points + " stop when " + max + " " + match + " or at dist " + stop);
    	
    	/*
    	 * Mark all valid locations as processed (seen)
    	 * Remove duplicates.
    	 * Count matching units
    	 */
    	for (Iterator<MapLocation> iterator = edge.iterator(); iterator.hasNext();) {
    	    MapLocation m = iterator.next();
    	    int x = m.getX(), y = m.getY();
    	    if (!open[x][y] && map.passable(x, y)) {
    	    	open[x][y] = true;
    	    	if (isMatch(m, match, ignoreWorkers))
					matchCount++;
    	    } else
    			iterator.remove();
    	}

    	/*
    	 * This is a modified Breadth First Search
    	 * Since we want to know the distance from the source we maintain a current open list (edge)
    	 * and build up the next open list (nextEdge). When edge is processed (empty) we increment the distance and switch to the nextEdge
    	 */
    	while (!edge.isEmpty()) {
    		distance++;
    		if (stop > 0 && distance >= stop) {
    			//debug(3, "Ripple stop distance " + stop + " reached");
    			return;
    		}
    		double gravity = points/(distance*distance);
    		LinkedList<MapLocation> nextEdge = new LinkedList<MapLocation>();
    		
        	for (MapLocation me: edge) {
        		int x = me.getX(), y = me.getY();
        		boolean addNeighbours = true;  
    			//Score this tile
        		if (gravityMap != null)
        			gravityMap[x][y] += gravity;
        		else { //Rockets
        			for (double[][] m:allMaps) //Add to all maps but ignore workers before round 700
	        			if (m != workerMap || currentRound > 700)
	        				m[x][y] += gravity;
        		}
        		
        		if (distance == 1 && match != null && isMatch(me, match, ignoreWorkers)) //This is a starting tile that is already occupied by the right unit
					addNeighbours = false;
	       		
    			//We add adjacent tiles to the next search if they are traversable
				//To avoid pile ups we check to see if a unit is stuck at a location and potentially delay adding in the neighbours
        		if (addNeighbours) {
        			for (MapLocation t:map.passableNeighbours(me)) {
		    			if (!open[t.getX()][t.getY()]) {
			    			nextEdge.add(t);
			    			if (isMatch(t, match, ignoreWorkers))
			    				matchCount++;
			    			open[t.getX()][t.getY()] = true;
			    			//debug(4, "ripple Added " + t);
		    			}
		    		}
        		}
    		}
    		//debug(4, "Ripple distance " + distance + " edge size = " + nextEdge.size());
    		
    		edge = nextEdge;
    		if (matchCount >= max) {
    			//debug(3, "Ripple match count met at distance " + distance);
    			return;
    		}
    	}
    	
    	//debug(3, "Ripple queue empty: complete at distance " + distance);
    }
    
    public static void ripple(double[][] gravityMap, MapLocation t, double points, UnitType match, int max, int stop) {
    	LinkedList<MapLocation> edge = new LinkedList<MapLocation>();
    	edge.add(t);

    	ripple(gravityMap, edge, points, match, max, stop);
    }
    
    public static void ripple(double[][] gravityMap, int x, int y, double points, UnitType match, int max, int stop) {
    	LinkedList<MapLocation> edge = new LinkedList<MapLocation>();
    	edge.add(map.loc(x, y));

    	ripple(gravityMap, edge, points, match, max, stop);
    }
    
    /*
     * Fill all the gravity maps with random noise
     * Add in the danger zones for all but the knight map
     * Finally run a special version of ripple for each rocket to call in the required units to each one
     */
    private static void initGravityMaps() {   	 	
		
    	for (int x=0; x<map.width(); x++) {
    		for (int y=0; y<map.height(); y++) {
    			double noise = randomness.nextDouble() / 10000.0;
    			Unit u = units.unitAt(map.loc(x, y));
    			if (u != null && u.team() == myTeam && u.unitType() == UnitType.Factory)
    				noise = 0; //Don't randomly walk into factories
    			for (double[][] me:allMaps) {
	    			me[x][y] = noise - mapState.danger(x, y);    			
		    	}
	    	}
    	}
		
		/*
		 * The damagedMap is for all units who have lost half their health
		 */
    	if (unitsToHeal.size() > 0 && healers.size() > 0) {
    		LinkedList<MapLocation> healing = new LinkedList<MapLocation>();
    		
    		for (MapLocation h:healers)
    			healing.addAll(map.allLocationsWithin(h, 0, 30)); //All areas in range but ignoring our location
    		ripple(damagedMap, healing, 10, null, 1000, -1);
    	}
    	
    	if (myPlanet != Planet.Earth) //We process rockets next - nothing to do on Mars
    		return;
    	
    	/*
    	 * Add Rockets that are ready to board and have space
    	 * We only broadcast when we have spare units (more combat units than map w and h)
    	 * Or it is near the flood (turn 600 onwards)
    	 */

    	for (int z=0; z<zones; z++) {
    		ZoneState zone = zoneState[z];
	    	int totalCombatForce = zone.myLandUnits[UnitType.Knight.ordinal()] +
	    			zone.myLandUnits[UnitType.Ranger.ordinal()] +
	    			zone.myLandUnits[UnitType.Mage.ordinal()];
	    	int desiredUnits = (int)Math.max(map.width(), map.height());
	    	if (conquered || separated)
	    		desiredUnits = 0;
	    	int passengers = totalCombatForce - desiredUnits;

	    	if (conquered || currentRound > EvacuationRound) {
	    		LinkedList<MapLocation> allRockets = new LinkedList<MapLocation>();
	    		for (Unit r: zone.rockets)
	    			allRockets.add(r.location().mapLocation());
	    		ripple(null, allRockets, 10000000, null, 1000, -1); //Shout really loudly to all units
	    	} else if (passengers > 0) {
	    		for (Unit r: zone.rockets) {
	    			int request = Math.min(passengers,  (int)(r.structureMaxCapacity() - r.structureGarrison().size()));
	    			ripple(null, r.location().mapLocation(), currentRound, null, passengers, -1);
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
    	int rangerCount = 0;
    	for (int z=0; z<zones; z++)
    		rangerCount += zoneState[z].myLandUnits[UnitType.Ranger.ordinal()];
	    	
    	//Add enemies
    	LinkedList<MapLocation> targets = new LinkedList<MapLocation>();
    	for (Unit u:enemies) {
    		//We want to be at our attack distance from each enemy
    		MapLocation enemyLoc = u.location().mapLocation();
    		targets.addAll(map.allLocationsWithin(enemyLoc, 10, 50));
    	}

    	ripple(rangerMap, targets, 30, UnitType.Ranger, rangerCount, -1);
    	
    	//If no enemies - explore
    	if (enemies.size() == 0) {
    		LinkedList<MapLocation> explore = (currentRound < 200 && enemyLocs.size() > 0)?enemyLocs:mapState.exploreZone();
	    	ripple(rangerMap, explore, 30, UnitType.Ranger, rangerCount, -1);
    	}
    }
    
    private static void updateMageMap() {
    	if (mageMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	mageMapLastUpdated = currentRound;
    	int mageCount = 0;
    	for (int z=0; z<zones; z++)
    		mageCount += zoneState[z].myLandUnits[UnitType.Mage.ordinal()];
	    	
    	//Add enemies
    	LinkedList<MapLocation> targets = new LinkedList<MapLocation>();
    	for (Unit u:enemies) {
    		//We want to be at our attack distance from each enemy
    		MapLocation enemyLoc = u.location().mapLocation();
    		targets.addAll(map.allLocationsWithin(enemyLoc, 8, 30));
    	}
    	ripple(mageMap, targets, 30, UnitType.Mage, mageCount, -1);
    }
    
    /*
     * Healers need to move towards damaged allies (not structures)
     * and away from enemies
     */
    private static void updateHealerMap() {
    	if (healerMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	healerMapLastUpdated = currentRound;
    	int healerCount = 0;
    	for (int z=0; z<zones; z++)
	    	healerCount += zoneState[z].myLandUnits[UnitType.Healer.ordinal()];
	    	
    	//Add damaged units
    	ripple(healerMap, unitsToHeal, 20, UnitType.Healer, healerCount, -1);
    	
    	//Avoid all enemies
    	ripple(healerMap, combatants, -5, UnitType.Healer, healerCount, 8);
    }
    
    private static void updateKnightMap() {
    	if (knightMapLastUpdated == currentRound) //We have already done it
    		return;
    	
    	knightMapLastUpdated = currentRound;
    	int knightCount = 0;
    	for (int z=0; z<zones; z++)
	    	knightCount += zoneState[z].myLandUnits[UnitType.Knight.ordinal()];
	    	
    	LinkedList<MapLocation> targets = new LinkedList<MapLocation>();
    	//Add enemies
    	for (Unit u:enemies)
    		targets.add(u.location().mapLocation());  	
    	ripple(knightMap, targets, 30, UnitType.Knight, knightCount, -1);
    	
    	//If no enemies - explore
    	if (enemies.size() == 0) {
    		LinkedList<MapLocation> explore = (currentRound < 200 && enemyLocs.size() > 0)?enemyLocs:mapState.exploreZone();
    		ripple(knightMap, explore, 30, UnitType.Knight, knightCount, -1);
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
    	int workerCount = 0;
    	for (int z=0; z<zones; z++) {
    		ZoneState zone = zoneState[z];
    		int zoneWorkers = zone.myLandUnits[UnitType.Worker.ordinal()];
			workerCount += zoneWorkers;
			//Add blueprints and damaged buildings - this is usually a small list so do them individually
			for (MapLocation m: zone.unitsToBuild) {
				LinkedList<MapLocation> workSpace = new LinkedList<MapLocation>();
				
				for (MapLocation p: map.passableNeighbours(m)) {
					Unit u = units.unitAt(p);
					if (u == null || (!u.unitType().equals(UnitType.Factory) && !u.unitType().equals(UnitType.Rocket)))
						workSpace.add(p);
				}

				ripple(workerMap, workSpace, 20, UnitType.Worker, Math.min(zoneWorkers, workSpace.size()), -1);
			}
    	}
    	
		/*
		 * Add Safe Karbonite deposits
		 * Split these into group according to how much karbonite is there
		 */
    	final int groups = 5;
    	@SuppressWarnings("unchecked")
		LinkedList<MapLocation>[] safe = new LinkedList[groups];
    	for (int i=0; i<groups; i++)
    		safe[i] = new LinkedList<MapLocation>();
    	
    	for (MapLocation m:karbonite.locations()) {
    		int x = m.getX(), y = m.getY();
    		if (mapState.danger(x, y) == 0) {
    			long k = karbonite.karboniteAt(x, y);
    			int group = (int)k / 10;
    			if (group >= groups)
    				group = groups -1;
    			safe[group].add(m);
    		}
    	}
    	
    	for (int i=0; i < groups; i++)
    		ripple(workerMap, safe[i], (i+1)*3, UnitType.Worker, workerCount, -1);
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
		map = new MapCache(gc.startingMap(myPlanet));
    	mapState = new MapState(map);
    	
    	int w = map.width(), h = map.height();
    	rangerMap = new double[w][h];
    	mageMap = new double[w][h];
    	healerMap = new double[w][h];
    	knightMap = new double[w][h];
    	workerMap = new double[w][h]; 
    	damagedMap = new double[w][h];
        allMaps = new double[][][] { mageMap, rangerMap, workerMap, knightMap, healerMap, damagedMap };

        MapAnalyser analysis = new MapAnalyser(gc, gc.startingMap(myPlanet), map); //Split the map into known zones
    	zones = analysis.zones.size();  	
        karbonite = new Karbonite(gc, map, zones);
    	
    	zoneState = new ZoneState[zones];
    	for (int z=0; z<zones; z++) {
    		zoneState[z] = new ZoneState();
    	}
    	
    	if (myPlanet == Planet.Mars) {
    		mars = analysis;
    	} else { //On earth we process mars to work out landing zones
    		mars = new MapAnalyser(gc, gc.startingMap(Planet.Mars), null);
    	 		
    		/*
    		 * Work out if we are in the same zone as an opponent
    		 * If not we can build units best suited for mars
    		 */
    		separated = false; //Set to true if we start in a zone where the enemy isn't
    		VecUnit start = gc.startingMap(myPlanet).getInitial_units();
    		HashSet<Integer> myZones = new HashSet<Integer>();
    		HashSet<Integer> enemyZones = new HashSet<Integer>();
    		
    		for (int i=0; i<start.size(); i++) {
    			Unit u = start.get(i);
    			MapLocation where = u.location().mapLocation();
    			if (u.team() == myTeam)
    				myZones.add(map.zone(where));
    			else {
    				enemyZones.add(map.zone(where));
    				enemyLocs.add(where);
    			}
    		}
    		
    		//Check to see if we are in any zones that the enemy isn't
    		for (int zone: myZones)
    			if (!enemyZones.contains(zone))
    				separated = true;
        	
        	//debug(1, "Earth has " + zones + " zones, separated = " + separated);
        	
        	if (separated) {
        		//Get to mars quickly
        		gc.queueResearch(UnitType.Rocket);
        	} else { //We share the zones	        	
        		for (int z=0; z<zones; z++) {
        			if (analysis.zones.get(z).tiles.size() < 60)
        				zoneState[analysis.zones.get(z).id].strategy = UnitType.Knight;
        			else if (analysis.zones.get(z).tiles.size() < 400)
        				zoneState[analysis.zones.get(z).id].strategy = UnitType.Mage;
        		}
        	}
    	}
    }   
	
	/*
	 * Find the best location to build a rocket
	 * Don't pick somewhere adjacent to another rocket
	 */
	private static MapLocation bestRocketLocation(MapLocation loc) {
		for (MapLocation test: allOpenNeighbours(loc)) {
    		if (mapState.danger(test.getX(), test.getY()) == 0) {
    			boolean hasRocket = false;
    			for (MapLocation m: map.passableNeighbours(test)) {
    				Unit u = units.unitAt(m);
    				if (u != null && u.unitType().equals(UnitType.Rocket))
    					hasRocket = true;
    			}
    			
    			if (!hasRocket)
    				return test;
    		}
    	}

		return null;
	}
    
	private static boolean ignoreDanger = false; //This can be set by a unit to allow it to ignore danger this turn
	
	/*
	 * Returns the location score from the given gravity map
	 * If the unit type is a ranger then we ignore the danger component of the score some of the time
	 */
	private static double locationScore(double[][] gravityMap, int x, int y, Unit u) {
		if (ignoreDanger)
			return gravityMap[x][y] + mapState.danger(x, y);

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
    	
    	//debug(4, "bestMove from " + myLoc + " current score " + bestScore);
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

    	//debug (4, "is " + best + " with a score of " + bestScore);
		return best;
    }
    
    /*
     * Decide what to research next based on the current strategy and game position
     */
    private static void updateResearch() {
    	ResearchInfo ri = gc.researchInfo();
    	
    	if (myPlanet != Planet.Earth || ri.hasNextInQueue())
    		return;
   
    	if (ri.getLevel(UnitType.Healer) < 3) { // Get Overcharge ASAP (25+100+100)
    		gc.queueResearch(UnitType.Healer);
    	} else if (ri.getLevel(UnitType.Mage) < 4) { // More damage (25+75+100+75)
    		gc.queueResearch(UnitType.Mage);
    	} else if (ri.getLevel(UnitType.Rocket) == 0) { //Time for rockets (50)
    		gc.queueResearch(UnitType.Rocket);
    	} else if (ri.getLevel(UnitType.Ranger) < 3) { // Build up to sniping (25+100+200)
    		gc.queueResearch(UnitType.Ranger);
    	}
    }
    

    private static int[] mySpaceUnits = new int[UnitType.values().length]; //Counts of how many units we have indexed by unit type (ordinal)
    private static LinkedList<MapLocation> enemyLocs = new LinkedList<MapLocation>(); //Start position of the enemy - used in place of the exploreZone at the start of the game
    private static boolean separated = false; //Set to true if we start off in different zones to the enemy
    private static LinkedList<Unit> enemies = new LinkedList<Unit>(); //List of all enemy units in sight
    private static LinkedList<Unit> workers = new LinkedList<Unit>(); //List of all our workers
    private static LinkedList<MapLocation> combatants = new LinkedList<MapLocation>(); //List of all combat worthy enemy units in sight
    private static LinkedList<MapLocation> enemyStructures = new LinkedList<MapLocation>();
    private static LinkedList<MapLocation> enemyHealers = new LinkedList<MapLocation>();
    private static LinkedList<MapLocation> enemyRangers = new LinkedList<MapLocation>();
    private static LinkedList<MapLocation> enemyOthers = new LinkedList<MapLocation>();
    private static LinkedList<MapLocation> healers = new LinkedList<MapLocation>();
    private static LinkedList<MapLocation> unitsToHeal = new LinkedList<MapLocation>(); //List of units that need healing
    
    /*
     * Loop through the units we are aware of and update our cache
     * Called once each turn
     */
    private static void updateUnits() {
        units.updateCache(); //All the units we can see
        unitsInSpace = gc.unitsInSpace(); //All the units in space
        
        for (int z=0; z<zones; z++)
        	zoneState[z].clear();
    	Arrays.fill(mySpaceUnits, 0);
    	mapState.clear();

    	enemies.clear();
    	combatants.clear();
    	enemyStructures.clear();
    	enemyHealers.clear();
    	enemyRangers.clear();
    	enemyOthers.clear();
    	healers.clear();
    	workers.clear();
    	unitsToHeal.clear();
		
    	VecUnit known = units.allUnits();
    	for (int i = 0; i < known.size(); i++) {
            Unit unit = known.get(i);
            
            if (unit.location().isOnMap()) {
        		MapLocation here = unit.location().mapLocation();
        		ZoneState zone = zoneState[map.zone(here)];
        		
            	if (unit.team() == myTeam) {
            		zone.myLandUnits[unit.unitType().ordinal()]++;
    		 		mapState.addVisibility(map.allLocationsWithin(here, -1, unit.visionRange()));
            		
            		if (unit.unitType().equals(UnitType.Factory) || unit.unitType().equals(UnitType.Rocket)) {
            			if (unit.structureIsBuilt() == 0 || unit.health() < unit.maxHealth())
            				zone.unitsToBuild.add(here);
	
            			if (unit.structureIsBuilt() > 0 && unit.unitType().equals(UnitType.Rocket) && unit.rocketIsUsed() == 0)
            				zone.rockets.add(unit);
            			
            			VecUnitID garrison = unit.structureGarrison();
            			for (int j=0; j<garrison.size(); j++) {
            				int id = garrison.get(j);
            				zone.myLandUnits[gc.unit(id).unitType().ordinal()]++;
            			}
            			
            			if (unit.unitType().equals(UnitType.Factory) && unit.isFactoryProducing() > 0) //See what we are producing and count that
            				zone.myLandUnits[unit.factoryUnitType().ordinal()]++;
            		} else {
                		if (unit.unitType() == UnitType.Healer)
                			healers.add(here);
                		if (unit.unitType() == UnitType.Worker)
                			workers.add(unit);
                		if (unit.health() < unit.maxHealth())
            				unitsToHeal.add(here);
            		}
            	} else { //enemies
            		enemies.add(unit);
            		zone.enemyUnits[unit.unitType().ordinal()]++;

            		switch (unit.unitType()) {
	            		case Factory:
	            			enemyStructures.add(here);
	            			if (unit.isFactoryProducing() > 0)
	            				zone.enemyUnits[unit.factoryUnitType().ordinal()]++;
	            			break;
	            		case Ranger:
	            			combatants.add(here);
	            			enemyRangers.add(here);
	            			for (MapLocation m:map.allLocationsWithin(here, unit.rangerCannotAttackRange(), unit.attackRange())) {
	            				int x = m.getX(), y = m.getY();
	            				mapState.addDanger(x, y, unit.damage());
	            			}
	            			break;
	            		case Knight: //Increase radius to 10 to account for them moving then attacking
	            			combatants.add(here);
	            			enemyOthers.add(here);
	            			for (MapLocation m:map.allLocationsWithin(here, -1, 10)) {
	            				int x = m.getX(), y = m.getY();
	            				mapState.addDanger(x, y, unit.damage()/2);
	            			}
	            			for (MapLocation m:map.passableNeighbours(here)) {
	            				int x = m.getX(), y = m.getY();
	            				mapState.addDanger(x, y, unit.damage()/2);
	            			}
	            			break;
	            		case Mage: //TODO - Increase radius to account for splash damage
	            			combatants.add(here);
	            			enemyOthers.add(here);
	            			for (MapLocation m:map.allLocationsWithin(here, -1, unit.attackRange())) {
	            				int x = m.getX(), y = m.getY();
	            				mapState.addDanger(x, y, unit.damage());
	            			}
	            			break;
	            		case Rocket: //These damage neighbours when they take off (so only dangerous on Earth)
	            			enemyStructures.add(here);
	            			if (myPlanet == Planet.Earth && unit.structureIsBuilt() > 0) { 
	            				for (MapLocation m:map.passableNeighbours(here)) {
		            				int x = m.getX(), y = m.getY();
		            				mapState.addDanger(x, y, 100);
		            			}
	            			}
	            			break;
	            		case Healer:
	            			enemyHealers.add(here);
	            			break;
	            		default:
	            			enemyOthers.add(here);
	            			break;
            		}
            	}
            }
    	}
    	
    	/*
    	 * Adjust our strategy according to enemies seen
    	 */
    	for (int z=0; z<zones; z++) {
    		ZoneState zone = zoneState[z];
			if (currentRound >= EvacuationRound)
				zone.strategy = UnitType.Mage;
			else if (currentRound >= 150)
	    		zone.strategy = UnitType.Ranger;
	    	else if (zone.strategy != UnitType.Ranger) {
	    		if (zone.strategy == UnitType.Mage && zone.enemyUnits[UnitType.Ranger.ordinal()] > 0) //Rangers beat Mages
	    			zone.strategy = UnitType.Knight;
	    		if (zone.strategy == UnitType.Knight && zone.enemyUnits[UnitType.Mage.ordinal()] > 0) //Mages beat Knights
	    			zone.strategy = UnitType.Ranger;
	    	}
			
			Collections.sort(zone.rockets, new Comparator<Unit>() {
	    		public int compare(Unit r1, Unit r2) {
	    			return r1.id() - r2.id();
	    		}
	    	});
    	}
    	
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
    	    if (mapState.visible(x, y))
    			iterator.remove();
    	}
    	
    	/*
    	 * Produce the list of locations that are unseen but next to a location we can see
    	 * i.e. the border of known space
    	 * If we have no units this list is empty
    	 */
    	if (!conquered && units.allUnits().size() > 0)
			mapState.explore();
    	
    	if (!conquered && myPlanet == Planet.Earth && mapState.explored() &&
    			enemies.size() == 0 && units.allUnits().size() > 0) {
    		conquered = true;
    		//debug(0, "Earth is conquered on round " + currentRound);
    	}
    	
    	//Look for any rockets arriving on Mars in the next 10 turns and mark the tiles
    	//around the landing site as dangerous
    	if (myPlanet == Planet.Mars) {
    		for (int r=0; r<10; r++) {
	    		VecRocketLanding landings = gc.rocketLandings().landingsOn(currentRound+r);
	    		for (int l=0; l<landings.size(); l++) {
	    			MapLocation site = landings.get(l).getDestination();
	    			//debug(2, "Clearing area for landing on round " + (currentRound+r) + " at " + site);
	    			mapState.addDanger(site.getX(), site.getY(), 100); //TODO find real value from interface
	    			for (MapLocation m:map.neighbours(site))
	    				mapState.addDanger(m.getX(), m.getY(), 100);
	    		}
    		}
    	}
    	
    	initGravityMaps(); //Gives each a random noise level and includes the danger areas
    	
    	updateBuildStrategy();
    }
    
    private static void manageWorker(Unit unit) {
    	if (!unit.unitType().equals(UnitType.Worker) || !unit.location().isOnMap())
    		return;
    	
    	int id = unit.id();
    	
    	//Do we want to move to a better location
        unit = moveUnit(unit, false);            
        
		MapLocation loc = unit.location().mapLocation();
		ZoneState zone = zoneState[map.zone(loc)];
		
		//Can we help build or repair something
    	for (MapLocation m:map.passableNeighbours(loc)) {			
			Unit other = units.unitAt(m);
			if (other != null && other.team() == myTeam &&
					(other.unitType() == UnitType.Factory || other.unitType() == UnitType.Rocket)) {
				if (gc.canBuild(id, other.id())) {
					gc.build(id, other.id());
					unit = units.updateUnit(id);
					//debug(2, "worker building");
				}
				if (other.health() < other.maxHealth() && gc.canRepair(id, other.id())) {
					gc.repair(id, other.id());
					unit = units.updateUnit(id);
  					//debug(2, "worker is repairing");
				}
			}
		}

		/*
		 * Now check to see if we want to blueprint a rocket
		 */		
    	long k = gc.karbonite(); 	   	   
		if (unit.workerHasActed() == 0 && myPlanet == Planet.Earth && zone.rocketsNeeded(currentRound) > 0 &&
				k >= bc.bcUnitTypeBlueprintCost(UnitType.Rocket)) {
	    	MapLocation buildLoc = null;
			
	    	/*
	    	 * Sometimes we block ourselves in due to excessive population (or a small planet)
	    	 * If we want to build a rocket and we have no rockets then we destroy an adjacent unit and build there!
	    	 */   
	    	if (zone.myLandUnits[UnitType.Rocket.ordinal()] == 0) { //We have no rockets but need one
		    	int spaces = 0;
		    	for (MapLocation m: map.passableNeighbours(loc)) {
		    		if (units.unitAt(m) == null)
		    			spaces++;
		    	}
		    	
		    	if (spaces == 0) { //We need a sacrifice
		    		Unit suicide = null;
		    		for (Unit u: senseNearbyUnits(loc, 2, myTeam)) {
		    			if (u.id() == unit.id())
		    				continue; //Don't kill ourself
		    			suicide = u;
		    			if (u.unitType() != UnitType.Factory)
		    				break;
		    		}
		    		if (suicide != null) {
		    			//debug(2, "Destroying " + suicide.unitType() + " to make room for a new structure");
		    			buildLoc = suicide.location().mapLocation();
		    			units.removeUnit(buildLoc);
		    			gc.disintegrateUnit(suicide.id());
		    		}
		    	}
	    	}
	    	
	    	if (buildLoc == null)
	    		buildLoc = bestRocketLocation(loc);		

	    	if (buildLoc != null) {
	    		Direction dir = loc.directionTo(buildLoc);
				if (gc.canBlueprint(id, UnitType.Rocket, dir)) {
					gc.blueprint(id, UnitType.Rocket, dir);
					units.updateUnit(buildLoc);
					unit = units.updateUnit(id);
					//debug(2, "worker blueprinting rocket");
					zone.myLandUnits[UnitType.Rocket.ordinal()]++;
				}
	    	}
		}
		
		//Can we Harvest? Pick the location with the most karbonite
		if (unit.workerHasActed() == 0) {
			long most = karbonite.karboniteAt(loc);
			MapLocation best = loc;
			for (MapLocation h: map.neighbours(loc)) {
				if (mapState.visible(h.getX(), h.getY()) && karbonite.karboniteAt(h) > most) {
					most = karbonite.karboniteAt(h);
					best = h;
				}
			}
			
			if (most > 0) {
				Direction d = loc.directionTo(best);
				if (gc.canHarvest(id, d)) {
					gc.harvest(id, d);
					unit = units.updateUnit(id);
					//debug(2, "worker harvesting");
					karbonite.harvest(best, unit);
				}
			}
		}
		
		//Check to see if we should replicate
    	boolean replicate = (!haltProduction && zone.myLandUnits[UnitType.Worker.ordinal()] < karbonite.maxWorkers(map.zone(loc)));
    	if (myPlanet == Planet.Earth) {
    		if (karbonite.locations().size() == 0 && zone.myLandUnits[UnitType.Factory.ordinal()] == 0)
    			replicate = false; //Save up for a factory
    	} else {
    		replicate = (currentRound > FloodRound); //Might as well spend all our karbonite
    	}

		//We can replicate even if we have acted
    	Direction dir = bestMove(unit, getGravityMap(unit.unitType()), true);
    	if (dir != null && replicate && gc.canReplicate(id, dir)) {
    		gc.replicate(id, dir);
    		//debug(2, "worker replicating");
    		unit = units.updateUnit(id);
    		zone.myLandUnits[UnitType.Worker.ordinal()]++;
    		Unit newWorker = units.updateUnit(loc.add(dir));
    		processUnit(newWorker);
    	}
      	
    	unit = moveUnit(unit, true);	
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
    			if ((full && arrivalNow <= arrivalNext) || takingDamage || currentRound == FloodRound) {
    				//Load everyone we can
    				for (MapLocation m:map.passableNeighbours(here)) {
    					Unit u = units.unitAt(m);
    					if (u != null && gc.canLoad(id, u.id())) {
    						gc.load(id, u.id());
    						//debug(2, "Rocket is loading " + u.unitType() + " before launch");
    						units.removeUnit(m);
    						unit = units.updateUnit(id);
    					}
    				}
    				//debug(2, "Launching rocket " + id + " to " + dest);
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
	    			Direction dir = bestMove(unit, getGravityMap(UnitType.Worker), true);
	    			if (dir != null && gc.canUnload(unit.id(), dir)) {
	    				gc.unload(unit.id(), dir);
	    				//debug(2, "Unloading from rocket - passing through");
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
	    		    	//debug(2, "unloading from rocket");
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
    	MapLocation loc = unit.location().mapLocation();
    	int zoneId = map.zone(loc);
    	ZoneState zone = zoneState[zoneId];
    	long garrisoned = unit.structureGarrison().size();

		/*
		 * Unload units if possible
		 * Pick the direction our strategy unit would move
		 */
		while (garrisoned > 0) {
			Direction dir = bestMove(unit, getGravityMap(zone.strategy), true);
			if (dir == null) { //No room - try to make space as we have probably got a combat unit inside
				for (Unit u: senseNearbyUnits(loc, 2, myTeam)) {
					if (u.unitType() == UnitType.Worker && gc.canLoad(fid, u.id())) {
						dir = loc.directionTo(u.location().mapLocation());
						units.removeUnit(u.location().mapLocation());
						gc.load(fid, u.id());
						unit = units.updateUnit(fid);
						break;
					}	
				}
			}
			if (dir != null && gc.canUnload(unit.id(), dir)) {
				gc.unload(unit.id(), dir);
				//debug(2, "Unloading from factory");
				MapLocation where = unit.location().mapLocation().add(dir);	        			
	    		processUnit(units.updateUnit(where));
			}
			garrisoned--;
			unit = units.updateUnit(fid);
		}
    	
    	/*
    	 * Produce units
    	 * 
    	 * The number of healers we need is determined by the state of play.
    	 * The algorithm is adaptive, i.e. we check to see how many units need healing and create healers accordingly
    	 * We have an upper bound equal to the number of our combat unit and a miniumum of 1/3 of that
    	 */
    	
    	if (FloodRound - currentRound < 30)
    		return; //No point adding more to the build queue as we don't have time to evacuate
    	
    	UnitType produce = zone.strategy;    	
    	int combatUnits = zone.myLandUnits[UnitType.Ranger.ordinal()] + zone.myLandUnits[UnitType.Mage.ordinal()] + zone.myLandUnits[UnitType.Knight.ordinal()];
    	int healers = unitsToHeal.size()*2;
    	if (healers > combatUnits)
    		healers = combatUnits;
    	else if (healers < combatUnits / 5)
    		healers = combatUnits / 5;
    	
		if (zone.myLandUnits[UnitType.Worker.ordinal()] == 0 ||
				zone.myLandUnits[UnitType.Worker.ordinal()] < Math.min(karbonite.maxWorkers(zoneId), (2+combatUnits)/4))
    		produce = UnitType.Worker;
    	else if (zone.myLandUnits[UnitType.Healer.ordinal()] < healers)
	    	produce = UnitType.Healer;
    	else if ((zone.myLandUnits[UnitType.Ranger.ordinal()] + 1)*2 <= zone.myLandUnits[UnitType.Mage.ordinal()])
    		produce = UnitType.Ranger; //Mages need the vision range of rangers
    	else if (currentRound > 180 && zone.myLandUnits[UnitType.Mage.ordinal()] < Math.min(3,  zone.myLandUnits[UnitType.Ranger.ordinal()])) //We are soon to get overcharge
    		produce = UnitType.Mage;  		
    	
    	if ((produce == UnitType.Worker || !haltProduction) && gc.canProduceRobot(fid, produce)) {
			gc.produceRobot(fid, produce);
			//debug(2, "Factory starts producing a " + produce);
		}

    	unit = units.updateUnit(fid); //Update garrison info
    }
    
    private static LinkedList<Unit> overchargeHealers(Unit unit) {
    	LinkedList<Unit> helpers = new LinkedList<Unit>();
    	
    	for (Unit h: senseNearbyUnits(unit.location().mapLocation(), 30, myTeam)) {
    		if (h.unitType().equals(UnitType.Healer) && gc.isOverchargeReady(h.id())) {
    			helpers.add(h);
    		}
    	}
    	
    	return helpers;
    }
    
    private static Unit furthestUnit(Unit me, LinkedList<Unit> others) {
    	Unit furthest = null;
    	for (Unit u: others) {
    		if (furthest == null || me.location().mapLocation().distanceSquaredTo(u.location().mapLocation()) > 
    									me.location().mapLocation().distanceSquaredTo(furthest.location().mapLocation()))
    			furthest = u;
    	}
    	return furthest;
    }
    
    /*
     * Check to see if there are enough healers in range to allow us to get in multiple attacks
     */
    private static boolean doOvercharge(Unit unit) {
    	if (gc.researchInfo().getLevel(UnitType.Healer) < 3)
    		return false; 	
    	
    	LinkedList<Unit> helpers = overchargeHealers(unit);
    	if (helpers.size() < 3)
    		return false;
    	
    	int targets = senseNearbyUnits(unit.location().mapLocation(), 70, otherTeam).size();
    	
    	if (targets < 3)
    		return false;
    	
    	return true;
    }
    
    /*
     * Mages are great targets for overcharge - so good we code specifically for them and get the healers to
     * overcharge us from within this function.
     */
    private static void manageMage(Unit unit) {
    	int id = unit.id();
    	
    	if (!unit.location().isOnMap())
    		return;
    	
    	boolean overcharge = doOvercharge(unit);   	
    	if (overcharge)
    		ignoreDanger = true;
    	
    	long initialHeat = unit.attackHeat() + unit.movementHeat();
    	if (unit.isAbilityUnlocked() > 0)
    		initialHeat += unit.abilityCooldown();
    	
    	do {
	    	//Do we want to blink to a better location
	    	if (gc.isAttackReady(id) && gc.isBlinkReady(id)) {
	    		MapLocation here = unit.location().mapLocation();
	    		//We can blink to best location in sight range
	    		updateMageMap();
	    		double bestScore = mageMap[here.getX()][here.getY()];
	    		MapLocation bestOption = here;
	    		for (MapLocation o:map.allLocationsWithin(here, -1, unit.abilityRange())) {
	    			if (mageMap[o.getX()][o.getY()] > bestScore && map.passable(o) &&
	    					mapState.visible(o.getX(), o.getY()) &&	units.unitAt(o) == null) {
	    				bestScore = mageMap[o.getX()][o.getY()];
	    				bestOption = o;
	    			}
	    		}
	    		if (here.distanceSquaredTo(bestOption) > (overcharge?0:2)) {
	    			units.removeUnit(here);
	    			gc.blink(id, bestOption);
	    			unit = units.updateUnit(id);
	    			//debug(2, "Mage is blinking to " + bestOption);
	    		}
	    	}
	    	
	    	unit = splashAttack(unit);
	    	if (unit == null)
	    		break; //We killed ourself
	    	
	        unit = moveUnit(unit, true);
	        if (!unit.location().isOnMap())
	        	break;

	    	unit = splashAttack(unit);
	    	if (unit == null)
	    		break; //We killed ourself
	        
	        if (overcharge) {
	        	//Check to see if we did anything (moved, blinked, fired)
	        	long myHeat = unit.attackHeat() + unit.movementHeat();
	        	if (unit.isAbilityUnlocked() > 0)
	        		myHeat += unit.abilityCooldown();
	        	
	        	if (myHeat <= initialHeat) //We did nothing
	        		overcharge = false;
	        	else {
		        	Unit helper = furthestUnit(unit, overchargeHealers(unit));
		        	if (helper != null) {
		        		gc.overcharge(helper.id(), unit.id());
		        		initialHeat = 0; //These are reset by the overcharge
		        		//debug(1, currentRound + ": Overcharging mage @ " + unit.location().mapLocation());
		        		units.updateUnit(helper.id());
		        		unit = units.updateUnit(unit.id());
		        	} else
		        		overcharge = false;
	        	}
	        }
    	} while (overcharge);
    	
    	ignoreDanger = false;
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
    	boolean inDanger = (mapState.danger(here) > 0);
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
    			//debug(2, "Sniping on " + target);
    			return;
    		}
    	}
    	
		if (!attacked && mapState.danger(here) < unit.health() && unitsToHeal.size() < healers.size())
			ignoreDanger = true;
		
        unit = moveUnit(unit, true);
        if (unit.location().isOnMap())
        	attackWeakest(unit);
        
        ignoreDanger = false; //Reset the global!
    }
    
    private static void manageKnight(Unit unit) {
    	if (!unit.location().isOnMap())
    		return;
    	
    	ignoreDanger = true;
    	if (unit.abilityHeat() < 10 && unit.isAbilityUnlocked() > 0) { //Check for javelin targets
        	int target = bestJavelinTarget(unit);
        	if (target > 0 && gc.canJavelin(unit.id(), target)) {
        		gc.javelin(unit.id(), target);
        		//debug(2, "Knight is throwing a javelin");
        		unit = units.updateUnit(unit.id());
        	}
        }
    	
    	unit = attackWeakest(unit); 
        unit = moveUnit(unit, true);
        if (unit.location().isOnMap())
    		unit = attackWeakest(unit); 
        ignoreDanger = false;
    }
    
    /*
     * manageHealer
     * 
     * We don't fight so we heal anyone in range
     */
    private static void manageHealer(Unit unit) {
    	if (!unit.location().isOnMap())
    		return;
    	
    	unit = moveUnit(unit, false);
    	
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
				//debug(2, "Healing " + unitToHeal.unitType() + " @ " + unitToHeal.location().mapLocation());
	    	}
    	}
    	
    	/*
    	 * Overcharge is handled by the units requesting it
    	 */
    	
    	unit = moveUnit(unit, true);    	
    }
}