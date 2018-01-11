// import the API.
// See xxx for the javadocs.
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import bc.*;

public class Player {
	private static GameController gc;
	private static Team myTeam; //Red or Blue
	private static Planet myPlanet; //(Earth or Mars)
    private static PlanetMap map;	//Initial map  
    private static VecUnit units; //The list of all known units - updated each turn in the main loop
    private static int totalKarbonite; //How much Karbonite is available on the starting map
    
    public static void main(String[] args) {
        // Connect to the manager, starting the game
        gc = new GameController();
        myTeam = gc.team();

        //Start our research
        gc.queueResearch(UnitType.Rocket); // Allows us to build rockets (100 turns)   
        gc.queueResearch(UnitType.Worker); // Improves Karbonite harvesting (25 turns)
        gc.queueResearch(UnitType.Ranger); // Increase movement rate (25 turns)
        gc.queueResearch(UnitType.Mage); //Increase Damage
        gc.queueResearch(UnitType.Mage); //Increase Damage
        gc.queueResearch(UnitType.Mage); //Increase Damage
        gc.queueResearch(UnitType.Mage); //Blink
        
        //Cache the initial map
        myPlanet = gc.planet();
        map = gc.startingMap(myPlanet);  	
    	scanMap();
    	debug("Total Karbonite on " + myPlanet + " is " + totalKarbonite);
        
        if (myPlanet == Planet.Earth) {
        	runEarth();
        } else if (myPlanet == Planet.Mars) {
        	runMars();
        }
    }
    
    private static void debug(String s) {
    	System.out.println(s);
    }
    
	/*
	 * The following stationary tiles generate gravity wells
	 * - Karbonite
	 * - Asteroid drops (future Karbonite)
	 * - Blueprints
	 * - Buildings to repair
	 * - Rockets
	 * - Enemy buildings
	 * - Tiles not seen in a while
	 * 
	 * The following mobile units do the same
	 * - Enemy units
	 * - Workers (to attract combat allies to defend them)
	 * - Damaged allies
	 * - Ranger units (attract 3 mages and a healer)
	 * 
	 * Our units build up a gravity well using the following rules
	 * All units
	 * - Tiles not seen - Strength = turns since last seen, Max = 1
	 * 
	 * Workers
	 * - Karbonite - Strength = Karbonite at location, Max workers = empty locations next to Karbonite
	 * - Asteroid drops - as above but strength reduced according to how long before drop
	 * - Blueprints - Strength = 200, Max = empty locations
	 * - Damaged buildings - Strength = Damage, Max = empty locations
	 * - Rockets - Strength = 1000, Max = capacity
	 * 
	 * Healers
	 * - Damaged allies - Strength = Damage + Cost, Max = 1
	 * - Ranger ally - Strength = 10, Max = 1
	 * - Rocket - Strength = 1000, Max = 1
	 * 
	 * Mages
	 * - Enemy unit - Strength = 5*Cost, Max = 5
	 * - Ranger ally - Strength = 10, Max = 3
	 * - Worker ally - Strength = Cost, Max = 1
	 * - Rocket - Strength = 1000, Max = 3
	 * 
	 * Ranger
	 * - Enemy unit - Strength = 5*Cost, Max = 5
	 * - Worker ally - Strength = Cost, Max = 1
	 * - Rocket - Strength = 1000, Max = 1
	 */
    
    /*
     * The gravity well maps are stored in arrays that map onto the Planet map
     * High scores are more interesting - units move to an adjacent tile with a higher score if possible
     * 
     * We create these maps each turn that a unit needs to move and only as needed
     * i.e. if we have a healer that has a move cooldown < 10 we create the gravity map for it (and all healers)
     */
    private static long[][] karboniteMap; //Initialised with starting values and updated as we sense tiles with new values
    private static int[][] neighbours; //How many open tiles are adjacent to each tile
    private static boolean[][] passable; //Set to true if the location is passable (i.e. not water) - fixed
    private static Unit[][] unitAt; //Updated each turn with all the units we know about
    
    private static double[][] workerMap;
    private static double[][] mageMap;
    private static double[][] rangerMap;
    private static double[][] healerMap;
    
    private static boolean structureAt(int x, int y) {
    	if (unitAt[x][y] == null)
    		return false;
    	if (unitAt[x][y].unitType() == UnitType.Factory)
    		return true;
    	if (unitAt[x][y].unitType() == UnitType.Rocket)
    		return true;
    	return false;
    }
    
    /*
     * Ripple out from the given edge (set of points) until a given number of our units have been found scoring each tile as we go - the nearer the higher the score
     * For each location we mark it as open (i.e. on the list to process), closed (processed) or unseen
     */
    public static void ripple(double[][] gravityMap, List<MapLocation> edge, double points, UnitType match, int max) {
    	int UNSEEN = 0, OPEN = 1, CLOSED = 2;
    	int[][] status = new int[(int)map.getWidth()][(int)map.getHeight()];
    	int distance = 0; //How far from the source are we
    	int matchCount = 0; //How many units of the right type have we seen
    	
    	for (MapLocation me: edge) {
    		status[me.getX()][me.getY()] = OPEN;
    	}
    	
    	/*
    	 * This is a standard Breadth First Search
    	 * Since we want to know the distance from the source we maintain a current open list (edge)
    	 * and build up the next open list (nextEdge). When edge is processed (empty) we increment the distance and switch to the nextEdge
    	 */
    	while (!edge.isEmpty()) {
    		distance++;
    		List<MapLocation> nextEdge = new LinkedList<MapLocation>();
    		
        	for (MapLocation me: edge) {
        		if (status[me.getX()][me.getY()] != CLOSED) {
    				Unit unit = unitAt[me.getX()][me.getY()];
    				if (unit != null && unit.team() == myTeam) {
    					if (match == null || match == unit.unitType()) {
    						matchCount++;
    						if (distance > 1 && matchCount >= max) { //We have reached the cutoff point
    							//debug("Ripple match count met: complete at distance " + distance);
    							return;
    						}
    					}
    				}
        			
	        		status[me.getX()][me.getY()] = CLOSED;
	        		//debug("Ripple SEEN " + me);

	    			//Score this tile
					gravityMap[me.getX()][me.getY()] += points/(distance*distance);
		       		
	    			//We add adjacent tiles to the next search if they are traversable
		    		for (Direction direction: Direction.values()) {
		    			MapLocation t = me.add(direction);
		    			if (map.onMap(t) && status[t.getX()][t.getY()] == UNSEEN && passable[t.getX()][t.getY()] && !structureAt(t.getX(), t.getY())) {
			    			nextEdge.add(t);
			    			status[t.getX()][t.getY()] = OPEN;
			    			//debug("ripple Added " + t);
		    			}
		    		}
    			}
    		}
    		//debug("Ripple distance " + distance + " edge size = " + nextEdge.size());
    		
    		edge = nextEdge;
    	}
    	
    	//debug("Ripple queue empty: complete at distance " + distance);
    }
    
    public static void ripple(double[][] gravityMap, MapLocation t, double points, UnitType match, int max) {
    	List<MapLocation> edge = new LinkedList<MapLocation>();
    	edge.add(t);

    	//debug("ripple from " + t + " constraints Unit (" + match + ") * " + max);
    	ripple(gravityMap, edge, points, match, max);
    }
    
    private static void updateWorkerMap() {
    	if (workerMap == null) {
    		workerMap = new double[(int) map.getWidth()][(int) map.getHeight()];
    		
    		//Add Karbonite deposits
    		for (int x=0; x<map.getWidth(); x++)
    			for (int y=0; y<map.getHeight(); y++) {
    				if (karboniteMap[x][y] > 0) {
    					ripple(workerMap, new MapLocation(myPlanet, x, y), (double)karboniteMap[x][y], UnitType.Worker, Math.min(myUnitCounts[UnitType.Worker.ordinal()], neighbours[x][y]));
    				}
    			}
    	}
    }
    
    /*
     * Returns the number of tiles adjacent to the one given that are accessible
     * We use the initial map state rather than sense data
     */
    private static int countNeighbours(int x, int y) {
    	int result = 0;
    	for (int i=-1; i<=1; i++)
    		for (int j=-1; j<=1; j++) {
    			MapLocation test = new MapLocation(myPlanet, x+i, y+j);
    			if (map.onMap(test) && map.isPassableTerrainAt(test) > 0) {
	    			if (i == 0 && j == 0) //Record our cell state is a separate array for faster access later
	    				passable[x][y] = true;
	    			else
	    				result++;
    			}
    		}
    	
    	return result;
    }
    
    /*
     * Scan the map data and record the locations of karbonite, the number of passable neighbours each tile has
     * and return the total karbonite on the map
     * 
     * This is called once on the first turn
     */
    private static void scanMap() {
    	totalKarbonite = 0;
    	karboniteMap = new long[(int) map.getWidth()][(int) map.getHeight()];
    	neighbours = new int[(int) map.getWidth()][(int) map.getHeight()];
    	passable = new boolean[(int) map.getWidth()][(int) map.getHeight()];
    	
    	for (int x = 0; x<map.getWidth(); x++) {
    		for (int y=0; y<map.getHeight(); y++) {
    			karboniteMap[x][y] = map.initialKarboniteAt(new MapLocation(myPlanet, x, y));
    			totalKarbonite += karboniteMap[x][y];
    			neighbours[x][y] = countNeighbours(x,y);
    		}
    	}
    }
    
    private static Direction bestMove(Unit t, double[][] gravityMap) {
    	Direction best = null;
    	MapLocation myLoc = t.location().mapLocation();
    	double bestScore = gravityMap[myLoc.getX()][myLoc.getY()];
    	
    	for (Direction d: Direction.values()) {
    		MapLocation test = myLoc.add(d);
    		if (gravityMap[test.getX()][test.getY()] > bestScore && gc.canMove(t.id(), d)) {
    			bestScore = gravityMap[test.getX()][test.getY()];
    			best = d;
    		}
    	}
		return best;
    }
    
    //For all tiles we can sense
    private static void updateKarbonite() {
    	for (int x = 0; x<map.getWidth(); x++)
    		for (int y=0; y<map.getHeight(); y++) {
    			MapLocation test = new MapLocation(myPlanet, x, y);
    			if (gc.canSenseLocation(test))
    				karboniteMap[x][y] = gc.karboniteAt(test);
    		}
    }
    
    private static int[] myUnitCounts;
    /*
     * Loop through the units we are aware of and update our cache
     */
    private static void updateUnits() {
        units = gc.units();
    	unitAt = new Unit[(int) map.getWidth()][(int) map.getHeight()];
    	myUnitCounts = new int[UnitType.values().length];
    	
    	for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            if (unit.location().isOnMap()) {
            	int x = unit.location().mapLocation().getX();
            	int y = unit.location().mapLocation().getY();

            	unitAt[x][y] = unit;
            	if (unit.team() == myTeam) {
            		myUnitCounts[unit.unitType().ordinal()]++;
            	}
            }
    	}
    }
    
    /*
     * Returns an array containing all the passable neighbours of a map location
     * Passable means on the map and not water
     */
    private static MapLocation[] allPassableNeighbours(MapLocation l) {
    	List<MapLocation> result = new ArrayList<MapLocation>();
    	VecMapLocation all = gc.allLocationsWithin(l, 2);
    	
    	for (int i=0; i<all.size(); i++) {
    		MapLocation test = all.get(i);
			if (passable[test.getX()][test.getY()])
				result.add(test);
    	}
    	
    	return result.toArray(new MapLocation[result.size()]);
    }
   
    /*
     * Returns an array containing all the open neighbours of a map location
     * Open means on the map and not water and doesn't contain a unit
     */
    private static MapLocation[] allOpenNeighbours(MapLocation l) {
    	List<MapLocation> result = new ArrayList<MapLocation>();
    	VecMapLocation all = gc.allLocationsWithin(l, 2);
    	
    	for (int i=0; i<all.size(); i++) {
    		MapLocation test = all.get(i);
			if (passable[test.getX()][test.getY()] && unitAt[test.getX()][test.getY()] == null)
				result.add(test);
		}
    	
    	return result.toArray(new MapLocation[result.size()]);
    }
    
    private static void runEarth() {
    	
        while (true) {
        	try {
	            System.out.println("Current round on Earth: "+gc.round());

	            updateUnits();
	            updateKarbonite();
	            updateWorkerMap();
	            
	            for (int i = 0; i < units.size(); i++) {
	                Unit unit = units.get(i);
	                int id = unit.id();
	                
	                if (unit.team() == myTeam) {
		                // Most methods on gc take unit IDs, instead of the unit objects themselves.
		  
		                if (unit.unitType() == UnitType.Worker && unit.location().isOnMap()) {
		                	//Check to see if we can replicate
		                    if (unit.abilityCooldown() < 10) {
		                    	MapLocation[] options = allOpenNeighbours(unit.location().mapLocation());
		                    	Direction dir = unit.location().mapLocation().directionTo(options[0]);
		                    	if (options.length > 0 && gc.canReplicate(id, dir))
		                    		gc.replicate(id, dir);
		                    }
		                    
		                    if (gc.isMoveReady(id)) {
			                	Direction d = bestMove(unit, workerMap);
			                	if (d != null)
			                		gc.moveRobot(id, d);
			                }
		                    
		                    if (unit.workerHasActed() == 0) {
		                    	//Can we repair a structure
		                    	
		                    	//Can we work on a blueprint
		                    	
		                    	//Can we build a blueprint
		                    	
		                    	//Can we harvest
		                    }
		                }
	                }
	            }
	            // Submit the actions we've done, and wait for our next turn.
	            gc.nextTurn();
        	} catch (Exception e) {
        		//Ignore
        		debug("Caught exception " + e);
        		e.printStackTrace();
        	}
        }
    }
    
    private static void runMars() {
    	while (true) {
        	try {
	            System.out.println("Current round on Mars: "+gc.round());
	            VecUnit units = gc.myUnits();
	            for (int i = 0; i < units.size(); i++) {
	                Unit unit = units.get(i);
	
	                // Most methods on gc take unit IDs, instead of the unit objects themselves.
	                if (gc.isMoveReady(unit.id()) && gc.canMove(unit.id(), Direction.Southeast)) {
	                    gc.moveRobot(unit.id(), Direction.Southeast);
	                }
	            }
	            // Submit the actions we've done, and wait for our next turn.
	            gc.nextTurn();
        	} catch (Exception e) {
        		//Ignore
        	}
        }
    }
}