package frc.robot.simulation;

import edu.wpi.first.wpilibj.simulation.DutyCycleEncoderSim;
import java.util.function.BooleanSupplier;

/**
 * Simulates the arm as-if it were a real-world object. E.g. if the arm
 * is extended too far, it will break.
 */
public class ArmSimulation {
  private WinchSimulation m_winchSimulation;
  private DutyCycleEncoderSim m_winchAbsoluteEncoderSim;
  private double m_currentSignedDegrees;
  private boolean m_isCurrentSignedDegreesSet = false;
  private double m_topSignedDegreesLimit;
  private double m_bottomSignedDegreesLimit;
  private double m_grabberBreaksIfOpenBelowSignedDegreesLimit;
  private double m_encoderRotationsOffset;
  private BooleanSupplier m_grabberOpenSupplier = null;
  private boolean m_isBroken;
  private CalcArmAngleHelper m_calcArmAngleHelper;

  /**
   * Constructor.
   */
  public ArmSimulation(WinchSimulation winchSimulation,
      DutyCycleEncoderSim winchAbsoluteEncoderSim,
      double topRotationsLimit,
      double bottomRotationsLimit,
      double deltaRotationsBeforeBroken, // $TODO Just make caller pass in degrees
      double grabberBreaksIfOpenBelowThisLimit,
      double heightFromWinchToPivotPoint,
      double armLengthFromEdgeToPivot,
      double armLengthFromEdgeToPivotMin,
      double encoderRotationsOffset) {

    if (winchSimulation == null) {
      throw new IllegalArgumentException("winchSimulation");
    }

    if (armLengthFromEdgeToPivot < armLengthFromEdgeToPivotMin) {
      throw new IllegalArgumentException("armLengthFromEdgeToPivot needs to be at least "
          + armLengthFromEdgeToPivotMin + " meters, otherwise the arm cant be pivoted");
    }

    if (encoderRotationsOffset < 0 || encoderRotationsOffset >= 1) {
      throw new IllegalArgumentException("encoderRotationsOffset must be between 0 and 1");
    }

    m_topSignedDegreesLimit = toNonOffsetSignedDegrees(
        topRotationsLimit + deltaRotationsBeforeBroken,
        encoderRotationsOffset);
    m_bottomSignedDegreesLimit = toNonOffsetSignedDegrees(
        bottomRotationsLimit - deltaRotationsBeforeBroken,
        encoderRotationsOffset);
    m_grabberBreaksIfOpenBelowSignedDegreesLimit = toNonOffsetSignedDegrees(
        grabberBreaksIfOpenBelowThisLimit,
        encoderRotationsOffset);

    if (!UnitConversions.isInRightHalfPlane(m_topSignedDegreesLimit)) {
      throw new IllegalArgumentException("m_topSignedDegreesLimit must be between -90 and 90");
    }

    if (!UnitConversions.isInRightHalfPlane(m_bottomSignedDegreesLimit)) {
      throw new IllegalArgumentException("m_bottomSignedDegreesLimit must be between -90 and 90");
    }

    if (!UnitConversions.isInRightHalfPlane(m_grabberBreaksIfOpenBelowSignedDegreesLimit)) {
      throw new IllegalArgumentException(
          "m_grabberBreaksIfOpenBelowSignedDegreesLimit must be between -90 and 90");
    }

    if (m_topSignedDegreesLimit <= m_bottomSignedDegreesLimit) {
      throw new IllegalArgumentException(
          "m_topSignedDegreesLimit must be > m_bottomSignedDegreesLimit");
    }

    if (m_grabberBreaksIfOpenBelowSignedDegreesLimit >= m_topSignedDegreesLimit
        || m_grabberBreaksIfOpenBelowSignedDegreesLimit <= m_bottomSignedDegreesLimit) {
      throw new IllegalArgumentException(
          "m_grabberBreaksIfOpenBelowSignedDegreesLimit must be between "
              + "m_topSignedDegreesLimit and m_bottomSignedDegreesLimit");
    }

    m_winchSimulation = winchSimulation;
    m_winchAbsoluteEncoderSim = winchAbsoluteEncoderSim;
    m_encoderRotationsOffset = encoderRotationsOffset;
    m_isBroken = false;

    m_calcArmAngleHelper = new CalcArmAngleHelper(heightFromWinchToPivotPoint,
        armLengthFromEdgeToPivot);

    // Forces the absolute encoder to show the correct position
    updateAbsoluteEncoderPosition();
  }

  public boolean getIsBroken() {
    return m_isBroken;
  }

  public void periodic() {
  }

  // $TODO This is ugly - probably don't need a method
  public static double offsetArmRotationPosition(double position, double offset) {
    double positionWithOffset = position + offset;
    return positionWithOffset - Math.floor(positionWithOffset);
  }

  /**
   * $TODO This can be removed.
   */
  public static double toNonOffsetSignedDegrees(double position, double offset) {
    double positionWithoutOffset = offsetArmRotationPosition(position, -1 * offset);
    double degreesWithoutOffset = positionWithoutOffset * 360;
    return UnitConversions.toSignedDegrees(degreesWithoutOffset);
  }

  private boolean isInGrabberBreakRange(double positionSignedDegrees) {
    return UnitConversions.lessThanButNotEqualDouble(positionSignedDegrees,
        m_grabberBreaksIfOpenBelowSignedDegreesLimit);
  }

  private void updateAbsoluteEncoderPosition() {
    // If the arm is broken, there's nothing to update
    if (m_isBroken) {
      return;
    }

    boolean isGrabberOpen = false;
    if (m_grabberOpenSupplier != null) {
      isGrabberOpen = m_grabberOpenSupplier.getAsBoolean();
    }

    double newStringLen = m_winchSimulation.getStringUnspooledLen();
    CalcArmAngleHelper.Result resultPair = m_calcArmAngleHelper
        .calcSignedDegreesForStringLength(newStringLen);

    // Check if we got back that string length was invalid
    if (!resultPair.m_isValid) {
      System.out.println("ARM: Angle is out of bounds, needs to be in right half plane");
      m_isBroken = true;
    }

    double newAbsoluteEncoderSignedDegrees = resultPair.m_value;

    if (isGrabberOpen && m_isCurrentSignedDegreesSet
        && isInGrabberBreakRange(newAbsoluteEncoderSignedDegrees)) {

      if (!isInGrabberBreakRange(m_currentSignedDegrees)) {

        // If the arm is ABOUT to go into the breakable range with the grabber open, the arm gets
        // stuck but doesn't break
        System.out.println("ARM: Grabber is open while try to move arm to ground");

        // With grabber open, arm is STUCK and not able to go lower than a certain point
        newAbsoluteEncoderSignedDegrees = m_grabberBreaksIfOpenBelowSignedDegreesLimit;
      }
      else {

        // If the arm is ALREADY below a certain level, and grabber is broken, arm is broken
        System.out.println("ARM: Grabber is open while arm is in breakable range");
        m_isBroken = true;

        // Note that we don't let the arm move from where it was
        newAbsoluteEncoderSignedDegrees = m_currentSignedDegrees;
      }
    }

    if (newAbsoluteEncoderSignedDegrees > m_topSignedDegreesLimit) {
      System.out.println("ARM: Angle is above top limit of " + m_topSignedDegreesLimit);
      newAbsoluteEncoderSignedDegrees = m_topSignedDegreesLimit;
      m_isBroken = true;
    }

    if (newAbsoluteEncoderSignedDegrees < m_bottomSignedDegreesLimit) {
      System.out.println("ARM: Angle is below limit of " + m_bottomSignedDegreesLimit);
      newAbsoluteEncoderSignedDegrees = m_bottomSignedDegreesLimit;
      m_isBroken = true;
    }

    // Update the current position
    m_currentSignedDegrees = newAbsoluteEncoderSignedDegrees;
    m_isCurrentSignedDegreesSet = true;

    // $TODO cleanup
    double newAbsoluteEncoderNonSignedDegrees = UnitConversions
        .toUnsignedDegrees(newAbsoluteEncoderSignedDegrees);
    double newAbsoluteEncoderPosition = newAbsoluteEncoderNonSignedDegrees / 360.0;

    double newOffsetAbsoluteEncoderPosition = offsetArmRotationPosition(newAbsoluteEncoderPosition,
        m_encoderRotationsOffset);

    m_winchAbsoluteEncoderSim.set(newOffsetAbsoluteEncoderPosition);
  }

  public void setGrabberOpenSupplier(BooleanSupplier grabberOpenSupplier) {
    m_grabberOpenSupplier = grabberOpenSupplier;
  }

  public void simulationPeriodic() {
    updateAbsoluteEncoderPosition();
  }
}
