/*
 * HeadsUp Agile
 * Copyright 2009-2012 Heads Up Development Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.headsupdev.agile.storage;

import org.headsupdev.agile.api.Manager;
import org.headsupdev.agile.api.User;
import org.headsupdev.agile.api.logging.Logger;
import org.headsupdev.agile.storage.issues.Duration;
import org.headsupdev.agile.storage.issues.DurationWorked;
import org.headsupdev.agile.storage.issues.Issue;
import org.headsupdev.agile.storage.issues.Milestone;
import org.headsupdev.support.java.DateUtil;
import org.hibernate.Criteria;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility methods for working with DurationWorked calculations.
 *
 * @author Andrew Williams
 * @version $Id$
 * @since 1.0
 */
public class DurationWorkedUtil
{
    private static Logger log = Manager.getLogger( DurationWorkedUtil.class.getName() );

    /**
     * this will always return a duration object. it will be the most appropriate 'estimated time' remaining
     * for the issue in question on the dayInQuestion.
     *
     * @param issue
     * @param dayInQuestion
     * @return
     */
    public static Duration lastEstimateForDay( Issue issue, Date dayInQuestion )
    {

        Date endOfDayInQuestion = DateUtil.getEndOfDate(Calendar.getInstance(), dayInQuestion);
        Date lastEstimateDate = null; // used to know where our current estimate was based on
        Duration estimate = null;

        for ( DurationWorked worked : issue.getTimeWorked() )
        {
            Date workedDay = worked.getDay();
            if ( workedDay == null || worked.getUpdatedRequired() == null )
            {
                continue;
            }

            // for this Worked object to be considered valid it must be worked before the day in question
            if ( workedDay.before( endOfDayInQuestion ) )
            {
                if ( lastEstimateDate == null || workedDay.after( lastEstimateDate ) )
                {
                    estimate = worked.getUpdatedRequired();
                    lastEstimateDate = workedDay;
                }
            }
        }

        if ( estimate == null )
        {
            // if the issue was created before the dayInQuestion then we report the original issue time estimate
            // of if we want to backtrack the originalTimeEstimate
            if ( issue.getIncludeInInitialEstimates() || issue.getCreated().before( endOfDayInQuestion ) )
            {
                estimate = issue.getTimeEstimate();
            }
            else
            {
                // otherwise we report 0 hours for this issue vs dayInQuestion
                estimate = new Duration( 0 );
            }
        }

        return estimate;
    }

    public static Duration lastEstimateForIssue( Issue issue )
    {
        Duration estimate = issue.getTimeEstimate();

        Date lastEstimateDate = null;
        for ( DurationWorked worked : issue.getTimeWorked() )
        {
            if ( worked.getDay() == null || worked.getUpdatedRequired() == null )
            {
                continue;
            }

            if ( lastEstimateDate == null || ( worked.getDay() != null && worked.getDay().after( lastEstimateDate ) ) )
            {
                estimate = worked.getUpdatedRequired();
                lastEstimateDate = worked.getDay();
            }
        }

        return estimate;
    }

    public static Duration totalWorkedForDay( Issue issue, Date date )
    {
        Calendar cal = GregorianCalendar.getInstance();
        cal.setTime( date );

        double total = 0d;
        Calendar cal2 = GregorianCalendar.getInstance();

        for ( DurationWorked worked : issue.getTimeWorked() )
        {
            if ( worked.getDay() == null || worked.getUpdatedRequired() == null )
            {
                continue;
            }

            cal2.setTime( worked.getDay() );
            if ( cal.get( Calendar.DATE ) == cal2.get( Calendar.DATE ) &&
                    cal.get( Calendar.MONTH ) == cal2.get( Calendar.MONTH ) &&
                    cal.get( Calendar.YEAR ) == cal2.get( Calendar.YEAR ) )
            {
                if ( worked.getWorked() != null )
                {
                    total += worked.getWorked().getHours();
                }
            }
        }

        return new Duration( total );
    }

    public static Date getMilestoneStartDate( Milestone milestone )
    {
        Date startSet = milestone.getStartDate();
        if ( startSet != null )
        {
            return startSet;
        }

        Date due = milestone.getDueDate();
        if ( due == null )
        {
            due = new Date();
        }

        Calendar cal = GregorianCalendar.getInstance();
        cal.setTime( due );

        cal.add( Calendar.DATE, -14 );
        Date start = cal.getTime();

        for ( Issue issue : milestone.getIssues() )
        {
            for ( DurationWorked worked : issue.getTimeWorked() )
            {
                if ( worked.getDay() == null )
                {
                    continue;
                }

                if ( start.after( worked.getDay() ) )
                {
                    start = worked.getDay();
                }
            }
        }

        return start;
    }

    public static List<Date> getMilestoneDates( Milestone milestone, boolean includeDayBefore )
    {
        // some prep work to make sure we have valid dates for start and end of milestone
        Date dateEndMilestone = milestone.getDueDate();
        Date dateStartMilestone = DurationWorkedUtil.getMilestoneStartDate( milestone );
        List<Date> dates = new LinkedList<Date>();
        if ( dateEndMilestone == null || dateStartMilestone == null )
        {
            return dates;
        }

        Calendar calendar = Calendar.getInstance();
        Date confirmedEnd = DateUtil.getEndOfDate( calendar, dateEndMilestone );
        Date confirmedStart = DateUtil.getStartOfDate( calendar, dateStartMilestone );

        final boolean ignoreWeekend = Boolean.parseBoolean( milestone.getProject().getConfigurationValue(
                StoredProject.CONFIGURATION_TIMETRACKING_IGNOREWEEKEND ) );

        Calendar cal = GregorianCalendar.getInstance();
        cal.setTime( confirmedStart );

        if ( includeDayBefore )
        {
            // return the day before as a starting point for estimates - work can be logged after this, contributing to
            // a lower value at the end of the first day
            cal.add( Calendar.DATE, -1 );
        }
        boolean estimateDay = Boolean.parseBoolean( milestone.getProject().getConfigurationValue(
                StoredProject.CONFIGURATION_TIMETRACKING_BURNDOWN ) );
        for ( Date date = cal.getTime(); date.before( confirmedEnd ); date = cal.getTime() )
        {
            if ( ignoreWeekend && !estimateDay && ( cal.get( Calendar.DAY_OF_WEEK ) == Calendar.SATURDAY ||
                    cal.get( Calendar.DAY_OF_WEEK ) == Calendar.SUNDAY ) )
            {
                cal.add( Calendar.DATE, 1 );
                continue;
            }

            dates.add( date );

            cal.add( Calendar.DATE, 1 );
            estimateDay = false;
        }

        return dates;
    }

    /**
     * this will return an array of durations that match to the dates on the milestone in date order.
     * index 0 will show the effort remaining at end of day 1 , etc.
     *
     * @param milestone
     * @return
     */
    public static Duration[] getMilestoneEffortRequired( Milestone milestone )
    {
        if ( milestone == null )
        {
            return null;
        }

        List<Date> milestoneDates = getMilestoneDates( milestone, false );
        if ( milestoneDates == null || milestoneDates.size() == 0 )
        {
            return null;
        }
        // TODO fix getMilestoneDates to work correctly ....
        // manually add the start date to the milestone dates list as getMilestoneDates acts weird.
        Calendar calendar = Calendar.getInstance();
        calendar.setTime( milestoneDates.get( 0 ) );
        calendar.add( Calendar.DATE, -1 );

        List<Date> dates = new ArrayList<Date>();
        dates.add( calendar.getTime() );
        for ( Date date : milestoneDates )
        {
            dates.add( date );
        }

        Set<Issue> issues = milestone.getIssues();
        Duration[] effortRequired = new Duration[ dates.size() ];
        // initialise the returnArray if there are no issues on milestone.
        if ( issues == null || issues.size() == 0 )
        {
            for ( int i = 0; i < effortRequired.length; i++ )
            {
                effortRequired[ i ] = new Duration( 0 );
            }
            return effortRequired;
        }

        // iterate over each day and calculate the effort remaining.
        int dayIndex = 0;
        for ( Date date : dates )
        {
            double totalHoursForDay = 0;
            for ( Issue issue : issues )
            {
                Duration lastEstimate = lastEstimateForDay( issue, date );
                if ( lastEstimate != null )
                {
                    totalHoursForDay += lastEstimate.getHours();
                }
            }
            effortRequired[ dayIndex ] = new Duration( totalHoursForDay );

            dayIndex++;
        }

        return effortRequired;
    }

    public static double getMilestoneCompleteness( Milestone milestone )
    {
        final boolean timeEnabled = Boolean.parseBoolean( milestone.getProject().getConfigurationValue(
                StoredProject.CONFIGURATION_TIMETRACKING_ENABLED ) );
        final boolean timeBurndown = Boolean.parseBoolean( milestone.getProject().getConfigurationValue(
                StoredProject.CONFIGURATION_TIMETRACKING_BURNDOWN ) );

        double done = 0;
        double total = 0;
        for ( Issue issue : milestone.getIssues() )
        {
            double issueHours = 1;
            if ( timeEnabled && issue.getTimeEstimate() != null )
            {
                issueHours = issue.getTimeEstimate().getHours();
            }
            total += issueHours;

            if ( issue.getStatus() >= Issue.STATUS_RESOLVED )
            {
                done += issueHours;
                continue;
            }

            if ( !timeEnabled )
            {
                // add nothing to the done count for open issues...
                continue;
            }

            if ( timeBurndown )
            {
                Duration left = DurationWorkedUtil.lastEstimateForIssue( issue );
                if ( left != null )
                {
                    done += Math.max( issueHours - left.getHours(), 0 );
                }
            }
            else
            {
                Duration worked = DurationWorkedUtil.lastEstimateForIssue( issue );
                if ( worked != null )
                {
                    done += Math.min( worked.getHours(), issueHours );
                }
            }
        }

        return done / total;
    }

    public static Double getAverageVelocity()
    {
        if ( !averageVelocity.equals( Double.NaN ) )
        {
            return averageVelocity;
        }

        double velocities = 0.0;
        int velocityCount = 0;
        for ( User user : Manager.getSecurityInstance().getRealUsers() )
        {
            if ( !user.canLogin() )
            {
                continue;
            }

            Double velocity = getUserVelocity( user );
            if ( !velocity.equals( Double.NaN ) )
            {
                velocities += velocity;
                velocityCount++;
            }
        }

        averageVelocity = velocities / velocityCount;
        return averageVelocity;
    }

    // TODO we need to expire this once a week (or day)...
    private static Map<User, Double> userVelocities = new HashMap<User, Double>();
    private static Double averageVelocity = Double.NaN;

    public static Double getUserVelocity( User user )
    {
        if ( userVelocities.get( user ) != null )
        {
            return userVelocities.get( user );
        }

        List<DurationWorked> worked = getDurationWorkedForUser(user);
        Double velocity = calculateVelocity( worked, user );

        userVelocities.put( user, velocity );
        return velocity;
    }

    public static Double getCurrentUserVelocity( User user )
    {
//        if ( userVelocities.get( user ) != null )
//        {
//            return userVelocities.get( user );
//        }

        Calendar cal = Calendar.getInstance();
        cal.add( Calendar.WEEK_OF_YEAR, -1 );
        List<DurationWorked> worked = getDurationWorkedForUser(user, cal.getTime(), new Date());
        Double velocity = calculateVelocity( worked, user );

//        userVelocities.put( user, velocity );
        return velocity;
    }

    public static Double getUserVelocityInWeek( User user, Date week )
    {
// TODO cache this
//        if ( userVelocities.get( user ) != null )
//        {
//            return userVelocities.get( user );
//        }

        Calendar cal = Calendar.getInstance();
        cal.setTime( week );
        cal.add( Calendar.WEEK_OF_YEAR, 1 );
        // TODO midnight next week is not included now - right?
        cal.add( Calendar.MILLISECOND, -1 );

        List<DurationWorked> worked = getDurationWorkedForUser(user, week, cal.getTime());
        Double velocity = calculateVelocity( worked, user );

//        userVelocities.put( user, velocity );
        return velocity;
    }

    public static Double getVelocity( List<DurationWorked> worked, Milestone milestone )
    {
        Set<User> usersWorked = new HashSet<User>();

        // TODO fix issues around resources not working days they were allocated to...
        Date start = new Date();
        Date end = new Date( 0 );
        boolean calculateRange = true;
        if ( milestone.getStartDate() != null )
        {
            start = milestone.getStartDate();
            end = milestone.getDueDate();
            calculateRange = false;
        }

        for ( DurationWorked duration : worked )
        {
            usersWorked.add( duration.getUser() );

            if ( calculateRange )
            {
                if ( duration.getDay().before( start ) )
                {
                    start = DateUtil.getStartOfDate( Calendar.getInstance(), duration.getDay() );
                }
                if ( duration.getDay().after( end ) )
                {
                    end = DateUtil.getEndOfDate( Calendar.getInstance(), duration.getDay() );
                }
            }
        }

        Double velocities = 0.0;
        int velocityCount = 0;
        for ( User user : usersWorked )
        {
            if ( user.isHiddenInTimeTracking() )
            {
                continue;
            }

            Double vel = calculateVelocity( worked, user );
            if ( !vel.equals( Double.NaN ) )
            {
                velocities += vel;
                velocityCount++;
            }
        }

        return velocities / velocityCount;
    }

    private static Double calculateVelocity( List<DurationWorked> workedList, User user )
    {
        if ( user.isHiddenInTimeTracking() )
        {
            return Double.NaN;
        }

        double estimatedHoursWorked = 0.0;
        double daysWorked = 0.0;
        Set<Date> daysSeen = new HashSet<Date>();
        Calendar cal = Calendar.getInstance();

        Set<Issue> relevantIssues = new HashSet<Issue>();
        for ( DurationWorked duration : workedList )
        {
            try
            {
                if ( duration.getIssue() != null && !relevantIssues.contains( duration.getIssue() ) )
                {
                    relevantIssues.add( duration.getIssue() );
                }
            }
            catch ( ObjectNotFoundException e )
            {
                // ignore - TODO find a better way of handling this...
            }
        }

        for ( Issue issue : relevantIssues )
        {
            if ( issue.getTimeEstimate() == null || issue.getTimeEstimate().getHours() == 0 )
            {
                continue;
            }

            double estimate = issue.getTimeEstimate().getHours();
            double hoursWorked = 0;
            double totalEstimated = 0;

            List<DurationWorked> listWorked = new ArrayList<DurationWorked>( issue.getTimeWorked() );
            Collections.sort( listWorked, new Comparator<DurationWorked>()
            {
                public int compare( DurationWorked d1, DurationWorked d2 )
                {
                    Date date1 = d1.getDay();
                    Date date2 = d2.getDay();

                    if ( date1.equals( date2 ) )
                    {
                        double d1hours = 0;
                        if ( d1.getUpdatedRequired() != null )
                        {
                            d1hours = d1.getUpdatedRequired().getHours();
                        }
                        double d2hours = 0;
                        if ( d2.getUpdatedRequired() != null )
                        {
                            d2hours = d2.getUpdatedRequired().getHours();
                        }
                        return Double.compare( d1hours, d2hours );
                    }
                    return date1.compareTo( date2 );
                }
            } );

            for ( DurationWorked worked : listWorked )
            {
                // ignore empty work but respect it's estimate
                if ( worked.getWorked() == null || worked.getWorked().getHours() == 0 )
                {
                    if ( worked.getUpdatedRequired() != null )
                    {
                        estimate = worked.getUpdatedRequired().getHours();
                    }
                    continue;
                }

                // don't count work not in the list but respect new estimates
                if ( !workedList.contains( worked ) )
                {
                    if ( worked.getUpdatedRequired() != null )
                    {
                        estimate = worked.getUpdatedRequired().getHours();
                    }
                    continue;
                }

                if ( worked.getUser().equals( user ) )
                {
                    hoursWorked += worked.getWorked().getHours();
                    if ( worked.getUpdatedRequired() != null )
                    {
                        totalEstimated += estimate - worked.getUpdatedRequired().getHours();
                    }

                    // check if this is a new day
                    cal.setTime( DateUtil.getStartOfDate( cal, worked.getDay() ) );
                    if ( !daysSeen.contains( cal.getTime() ) )
                    {
                        daysWorked += 1.0;
                        daysSeen.add( cal.getTime() );
                    }
                }

                if ( worked.getUpdatedRequired() != null )
                {
                    estimate = Math.min( estimate, worked.getUpdatedRequired().getHours() );
                }
            }

            if ( hoursWorked == 0 )
            {
                continue;
            }

            estimatedHoursWorked += totalEstimated;
        }

        double velocity = 0;
        if ( daysWorked > 0 )
        {
            velocity = ( estimatedHoursWorked / daysWorked );
        }
        log.debug( " Velocity " + velocity + " for user " + user + " based on " + estimatedHoursWorked + " over " +
                daysWorked + " days" );
        return velocity;
    }

    public static Double getUserHoursLogged( User user )
    {
// TODO cache
//        if ( userVelocities.get( user ) != null )
//        {
//            return userVelocities.get( user );
//        }

        Double logged = calculateHoursLogged( getDurationWorkedForUser( user ), user );

//        userVelocities.put( user, logged );
        return logged;
    }

    public static Double getUserHoursLoggedInWeek( User user, Date week )
    {
// TODO cache this
//        if ( userVelocities.get( user ) != null )
//        {
//            return userVelocities.get( user );
//        }

        Calendar cal = Calendar.getInstance();
        cal.setTime( week );
        cal.add( Calendar.WEEK_OF_YEAR, 1 );
        // TODO midnight next week is not included now - right?
        cal.add( Calendar.MILLISECOND, -1 );
        Double logged = calculateHoursLogged( getDurationWorkedForUser( user, week, cal.getTime() ), user );

//        userVelocities.put( user, velocity );
        return logged;
    }

    private static Double calculateHoursLogged( List<DurationWorked> workedList, User user )
    {
        double total = 0;
        int daysWorked = 0;
        Set<Date> daysSeen = new HashSet<Date>();
        Calendar cal = Calendar.getInstance();

        for ( DurationWorked worked : workedList )
        {
            if ( worked.getUser().equals( user ) && worked.getWorked() != null )
            {
                total += worked.getWorked().getHours();

                // check if this is a new day
                cal.setTime( worked.getDay() );
                cal.set( Calendar.HOUR_OF_DAY, 0 );
                cal.set( Calendar.MINUTE, 0 );
                cal.set( Calendar.SECOND, 0 );
                cal.set( Calendar.MILLISECOND, 0 );
                if ( !daysSeen.contains( cal.getTime() ) )
                {
                    daysWorked++;
                    daysSeen.add( cal.getTime() );
                }
            }
        }

        return total / daysWorked;
    }

    private static List<Issue> getAllIssuesWithDurationWorked()
    {
        List<Issue> allIssues = new ArrayList<Issue>();
        Session session = ( (HibernateStorage) Manager.getStorageInstance() ).getHibernateSession();

        Criteria c = session.createCriteria( Issue.class );
        for ( Issue issue : (List<Issue>) c.list() )
        {
            if ( issue.getTimeWorked() != null && issue.getTimeWorked().size() > 0 )
            {
                allIssues.add( issue );
            }
        }

        return allIssues;
    }

    private static List<DurationWorked> getDurationWorkedForUser( User user )
    {
        Session session = ( (HibernateStorage) Manager.getStorageInstance() ).getHibernateSession();

        Criteria c = session.createCriteria( DurationWorked.class );
        c.add( Restrictions.eq( "user", user ) );
        c.add( Restrictions.gt( "worked.time", 0 ) );

        return c.list();
    }

    private static List<DurationWorked> getDurationWorkedForUser( User user, Date start, Date end )
    {
        List<DurationWorked> workedList = new ArrayList<DurationWorked>();
        Session session = ( (HibernateStorage) Manager.getStorageInstance() ).getHibernateSession();

        Criteria c = session.createCriteria( DurationWorked.class );
        c.add( Restrictions.eq( "user", user ) );
        c.add( Restrictions.gt( "worked.time", 0 ) );
        c.add( Restrictions.between("day", start, end) );

        for ( DurationWorked worked : (List<DurationWorked>) c.list() )
        {
            if ( worked.getDay().before( start ) || worked.getDay().after( end ) )
            {
                continue;
            }

            workedList.add( worked );
        }
        return workedList;
    }

    /**
     * This will sum together all duration logged against a user between start and end.
     * Depending on the types of Duration logged against this user, the smallest unit will be
     * represented in the return type.
     * <p/>
     * For example if there are two logged times, 1 hour and 1 day, the time unit
     * for the Duration returned will be composed of hours
     *
     * @return
     */
    public static Duration getLoggedTimeForUser( User user, Date start, Date end )
    {

        List<DurationWorked> workedList = getDurationWorkedForUser( user, start, end );
        double hoursLogged = 0;
        for ( DurationWorked worked : workedList )
        {
            hoursLogged += worked.getWorked().getHours();
        }

        return new Duration( hoursLogged );
    }
}
