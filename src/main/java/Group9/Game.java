package Group9;

import Group9.agent.container.AgentContainer;
import Group9.agent.container.GuardContainer;
import Group9.agent.container.IntruderContainer;
import Group9.map.GameMap;
import Group9.map.area.*;
import Group9.map.objects.*;
import Group9.map.dynamic.DynamicObject;
import Group9.map.dynamic.Pheromone;
import Group9.map.dynamic.Sound;
import Group9.math.Line;
import Group9.math.Vector2;
import Interop.Action.*;
import Interop.Agent.Guard;
import Interop.Agent.Intruder;
import Interop.Geometry.Angle;
import Interop.Geometry.Direction;
import Interop.Geometry.Distance;
import Interop.Geometry.Vector;
import Interop.Percept.AreaPercepts;
import Interop.Percept.GuardPercepts;
import Interop.Percept.IntruderPercepts;
import Interop.Percept.Scenario.ScenarioGuardPercepts;
import Interop.Percept.Scenario.ScenarioIntruderPercepts;
import Interop.Percept.Scenario.ScenarioPercepts;
import Interop.Percept.Smell.SmellPercept;
import Interop.Percept.Smell.SmellPerceptType;
import Interop.Percept.Smell.SmellPercepts;
import Interop.Percept.Sound.SoundPercept;
import Interop.Percept.Sound.SoundPerceptType;
import Interop.Percept.Sound.SoundPercepts;
import Interop.Percept.Vision.FieldOfView;
import Interop.Percept.Vision.ObjectPerceptType;
import Interop.Percept.Vision.ObjectPercepts;
import Interop.Percept.Vision.VisionPrecepts;

import java.util.*;
import java.util.stream.Collectors;

public class Game {

    private GameMap gameMap;
    private ScenarioPercepts scenarioPercepts;

    private List<GuardContainer> guards = new ArrayList<>();
    private List<IntruderContainer> intruders = new ArrayList<>();

    private Map<AgentContainer<?>, Boolean> actionSuccess = new HashMap<>();
    private Set<AgentContainer<?>> justTeleported = new HashSet<>();

    public Game(GameMap gameMap, int teamSize)
    {

        this.gameMap = gameMap;
        this.scenarioPercepts = gameMap.getScenarioPercepts();

        Spawn.Guard guardSpawn = gameMap.getObjects(Spawn.Guard.class).get(0);
        Spawn.Intruder intruderSpawn = gameMap.getObjects(Spawn.Intruder.class).get(0);

        AgentsFactory.createGuards(teamSize).forEach(a -> this.guards.add(new GuardContainer(a,
                guardSpawn.getContainer().getAsQuadrilateral().generateRandomLocation().toVexing(), new Vector(1, 1))));
        AgentsFactory.createIntruders(teamSize).forEach(a -> this.intruders.add(new IntruderContainer(a,
                intruderSpawn.getContainer().getAsQuadrilateral().generateRandomLocation().toVexing(), new Vector(1, 1))));
    }

    public void start()
    {

        while (true)
        {
            this.turn();
        }

    }

    public void turn()
    {

        this.cooldown();
        // Note: Intruders move first.

        //TODO we can ignore this for now since the ExplorerAgent is implementing the Guard interface, so we currently
        //  only have to support that
        /*
        for(IntruderAgent intruder : this.intruders)
        {
            final IntruderAction action = intruder.getAction(this.generateIntruderPercepts(intruder));
        }*/

        for(GuardContainer guard : this.guards)
        {
            final GuardAction action = guard.getAgent().getAction(this.generateGuardPercepts(guard));
            actionSuccess.put(guard, executeAction(guard, action));
        }

    }

    private <T> boolean executeAction(AgentContainer<T> agentContainer, Action action)
    {

        boolean isGuard = agentContainer.getAgent() instanceof Guard;
        boolean isIntruder = agentContainer.getAgent() instanceof Intruder;

        assert isGuard != isIntruder : "What m8?";

        if(action instanceof NoAction)
        {
            return true;
        }
        //TODO --- cleanup
        Set<EffectArea> effectAreas = gameMap.getEffectAreas(agentContainer);
        Optional<EffectArea> modifySpeedEffect = effectAreas.stream().filter(e -> e instanceof ModifySpeedEffect).findAny();
        Optional<EffectArea> soundEffect = effectAreas.stream().filter(e -> e instanceof SoundEffect).findAny();
        Optional<EffectArea> modifyViewEffect = effectAreas.stream().filter(e -> e instanceof ModifyViewEffect).findAny();
        //---

        if(action instanceof Move || action instanceof Sprint)
        {
            final double slowdownModifier = (double) modifySpeedEffect.orElseGet(NoModify::new).get(agentContainer);
            double distance = ((action instanceof Move) ?
                    ((Move) action).getDistance().getValue() : ((Sprint) action).getDistance().getValue()) * slowdownModifier;

            assert distance != -1;

            final double minSprint = isGuard ?
                    gameMap.getGuardMaxMoveDistance().getValue() : gameMap.getIntruderMaxMoveDistance().getValue();
            final double maxSprint = isGuard ?
                    gameMap.getGuardMaxMoveDistance().getValue() : gameMap.getIntruderMaxSprintDistance().getValue();

            boolean isSprinting = (distance > minSprint);

            if(isSprinting)
            {
                //--- guards are not allowed to sprint
                if(isGuard)
                {
                    return false;
                }
                else
                {
                    assert isIntruder;
                    if(((IntruderContainer) agentContainer.getAgent()).getSprintCooldown() > 0 || distance > maxSprint)
                    {
                        return false;
                    }
                }
            }

            //---


            //---

            final Vector2 end = agentContainer.getPosition().add(agentContainer.getDirection().mul(distance, distance));

            //TODO we are currently only checking whether a single line is intersecting with something but not whether
            // or not we are too wide
            Line line = new Line(agentContainer.getPosition(), end);
            if(gameMap.isRayIntersecting(line, ObjectPerceptType::isSolid))
            {
                return false;
            }

            if(isSprinting)
            {
                ((IntruderContainer) agentContainer.getAgent()).setSprintCooldown(gameMap.getSprintCooldown());
            }

            //--- move and then get new effects
            // TODO generate sound at start of movement
            agentContainer.move(distance);
            Set<EffectArea> movedEffectAreas = gameMap.getEffectAreas(agentContainer);
            soundEffect = movedEffectAreas.stream().filter(e -> e instanceof SoundEffect).findAny();


            Optional<EffectArea> locationEffect = movedEffectAreas.stream().filter(e -> e instanceof ModifyLocationEffect).findAny();

            if(!justTeleported.contains(agentContainer) && locationEffect.isPresent())
            {
                agentContainer.moveTo(((ModifyLocationEffect) locationEffect.get()).get(agentContainer));
                justTeleported.add(agentContainer);
            }
            else if(justTeleported.contains(agentContainer) && !locationEffect.isPresent())
            {
                justTeleported.remove(agentContainer);
            }

            soundEffect.ifPresent(effectArea -> {
                SoundEffect s = (SoundEffect) effectArea;
                gameMap.getDynamicObjects().add(new Sound(s.getType(), agentContainer,
                        s.get(agentContainer) * (distance / maxSprint), //TODO should be checked, but i am fairly certain that the radius is scaled linearly by speed of agent
                        1 //TODO check whether a sound exists for more than one round or not
                ));

            });
            return true;
        }
        else if(action instanceof Rotate)
        {
            Rotate rotate = (Rotate) action;
            if(gameMap.getScenarioPercepts().getMaxRotationAngle().getRadians() > rotate.getAngle().getRadians())
            {
                return false;
            }

            agentContainer.rotate(rotate.getAngle().getRadians());
            return true;
        }
        else if(action instanceof Yell)
        {
            // TODO Only guards can yell
            // TODO Every agent perceives yell
            gameMap.getDynamicObjects().add(new Sound(
                    SoundPerceptType.Yell,
                    agentContainer,
                    gameMap.getYellSoundRadius().getValue(),
                    1
            ));
            return true;
        }
        else if(action instanceof DropPheromone)
        {
            //TODO check whether there is already one in this place
            // TODO Pheromones have a radius, which decreases linearly with time
            DropPheromone dropPheromone = (DropPheromone) action;
            gameMap.getDynamicObjects().add(new Pheromone(
                    SmellPerceptType.Pheromone1, //TODO there is currently not a way to figure out which one it is...
                    agentContainer,
                    agentContainer.getPosition(),
                    scenarioPercepts.getRadiusPheromone().getValue(),
                    gameMap.getPheromoneExpireRounds()
            ));
            return true;
        }

        throw new IllegalArgumentException(String.format("Tried to execute an unsupported action: %s", action));

    }

    private void cooldown()
    {
        // --- iterate over dynamic objects (sounds) and adjust lifetime or remove
        {
            Iterator<DynamicObject> iterator = gameMap.getDynamicObjects().iterator();
            while (iterator.hasNext()) {
                DynamicObject e = iterator.next();
                e.setLifetime(e.getLifetime() - 1);
                if(e.getLifetime() == 0)
                {
                    iterator.remove();
                }

            }
        }

        // --- sprint cooldown
        this.intruders.forEach(e -> {
            if(e.getSprintCooldown() > 0)
            {
                e.setSprintCooldown(e.getSprintCooldown() - 1);
            }
        });

    }


    private GuardPercepts generateGuardPercepts(GuardContainer guard)
    {
        //TODO generate data structure for the specific agent
        return new GuardPercepts(
                generateVisionPercepts(guard),
                generateSoundPercepts(guard),
                generateSmellPercepts(guard),
                generateAreaPercepts(guard),
                new ScenarioGuardPercepts(this.gameMap.getScenarioPercepts(), this.gameMap.getGuardMaxMoveDistance()),
                this.actionSuccess.getOrDefault(guard, true)
        );
    }

    private IntruderPercepts generateIntruderPercepts(IntruderContainer intruder)
    {

        Vector2 direction = this.gameMap.getObjects(TargetArea.class).get(0).getContainer()
                                            .getCenter().sub(intruder.getDirection()).normalise();

        //TODO generate data structure for the specific agent
        return new IntruderPercepts(
                Direction.fromClockAngle(new Vector(direction.getX(), direction.getY())),
                generateVisionPercepts(intruder),
                generateSoundPercepts(intruder),
                generateSmellPercepts(intruder),
                generateAreaPercepts(intruder),
                new ScenarioIntruderPercepts(
                        this.gameMap.getScenarioPercepts(),
                        this.gameMap.getTurnsInTargetAreaToWin(),
                        this.gameMap.getIntruderMaxMoveDistance(),
                        this.gameMap.getIntruderMaxSprintDistance(),
                        intruder.getSprintCooldown()
                ),
                this.actionSuccess.getOrDefault(intruder, true)
        );
    }

    private <T> VisionPrecepts generateVisionPercepts(AgentContainer<T> agentContainer)
    {
        return new VisionPrecepts(new FieldOfView(new Distance(1), Angle.fromDegrees(1)), new ObjectPercepts(new HashSet<>()));
    }

    private <T> AreaPercepts generateAreaPercepts(AgentContainer<T> agentContainer)
    {
        return new AreaPercepts(
                gameMap.isInMapObject(agentContainer, Window.class),
                gameMap.isInMapObject(agentContainer, Door.class),
                gameMap.isInMapObject(agentContainer, SentryTower.class),
                justTeleported.contains(agentContainer)
        );
    }

    private <T> SoundPercepts generateSoundPercepts(AgentContainer<T> agentContainer)
    {
        return new SoundPercepts(this.gameMap.getDynamicObjects().stream()
                .filter(e -> e instanceof Sound)
                .map(dynamicObject -> {
                    Sound sound = (Sound) dynamicObject;
                    return new SoundPercept(
                            sound.getType(),
                            //TODO check whether this is correct or not...
                            Direction.fromRadians(dynamicObject.getCenter().getClockDirection() - agentContainer.getPosition().getClockDirection())
                    );
                }).collect(Collectors.toUnmodifiableSet()));
    }

    private <T> SmellPercepts generateSmellPercepts(AgentContainer<T> agentContainer)
    {
        //TODO verify that 'agentContainer.getClass().isAssignableFrom(e.getSource().getClass()' is checking that they
        // actually belong to the same team
        //TODO Check distance
        return new SmellPercepts(this.gameMap.getDynamicObjects().stream()
                .filter(e -> e instanceof Pheromone && agentContainer.getClass().isAssignableFrom(e.getSource().getClass()))
                .map(dynamicObject -> {
                    Pheromone pheromone = (Pheromone) dynamicObject;
                    return new SmellPercept(
                            pheromone.getType(),
                            new Distance(dynamicObject.getCenter().distance(agentContainer.getPosition()))
                    );
                }).collect(Collectors.toUnmodifiableSet()));
    }

}
