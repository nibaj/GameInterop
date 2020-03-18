package Group6.WorldState;

import Group6.Geometry.Distance;
import Group6.Geometry.Quadrilateral;
import Interop.Agent.Guard;
import Interop.Agent.Intruder;
import Group6.Geometry.Direction;
import Group6.Geometry.Point;

public class IntruderState extends AgentState {

    public IntruderState(Intruder intruder, Point location, Direction direction) {
        super(location, direction, 0, false, true);
        this.intruder = intruder;
    }

    private Intruder intruder;

    public Intruder getIntruder() {
        return intruder;
    }

    public void sprint(Distance distance, int cooldown) {
        addCooldown(cooldown);
        move(distance);
    }

    static IntruderState spawnIntruder(Scenario scenario, Intruder intruder) {
        // TODO: add check between other world state elements
        Quadrilateral spawnArea = scenario.getSpawnAreaIntruders();
        return new IntruderState(
            intruder,
            spawnArea.getRandomPointInside(),
            Direction.random()
        );
    }

}
