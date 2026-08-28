--- Licensed to the Apache Software Foundation (ASF) under one or more
--- contributor license agreements.  See the NOTICE file distributed with
--- this work for additional information regarding copyright ownership.
--- The ASF licenses this file to You under the Apache License, Version 2.0
--- (the "License"); you may not use this file except in compliance with
--- the License.  You may obtain a copy of the License at
---
---     http://www.apache.org/licenses/LICENSE-2.0
---
--- Unless required by applicable law or agreed to in writing, software
--- distributed under the License is distributed on an "AS IS" BASIS,
--- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
--- See the License for the specific language governing permissions and
--- limitations under the License.

--
-- The MySQL form of this schema. sdschema.sql alongside it is the
-- standard-SQL form, which is what any other engine needs.
--
-- The AUTO_INCREMENT= counters that used to sit on four of these tables came
-- from the database this file was dumped from -- 1143297 on dataPoint, 41 on
-- parameter -- and meant a fresh install started its ids partway up somebody
-- else's sequence. Removed.
--
-- `database` was NOT NULL with no default and createParameter has never
-- written it; outside strict mode MySQL substituted an empty string. Made
-- nullable, to say what was actually happening.
--
-- Table structure for table `dataPoint`
--

CREATE TABLE `dataPoint` (
  `dataPoint_id` int(10) unsigned NOT NULL auto_increment,
  `granule_id` int(10) unsigned NOT NULL,
  `dataset_id` int(10) unsigned NOT NULL,
  `parameter_id` int(10) unsigned NOT NULL,
  `time` datetime default NULL,
  `latitude` double default NULL,
  `longitude` double default NULL,
  `vertical` double default NULL,
  `value` double default NULL,
  PRIMARY KEY  (`dataPoint_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

--
-- Table structure for table `dataset`
--

CREATE TABLE `dataset` (
  `dataset_id` int(10) unsigned NOT NULL auto_increment,
  `longName` varchar(120) default NULL,
  `shortName` varchar(60) default NULL,
  `description` text,
  `source` varchar(255) default NULL,
  `referenceURL` varchar(255) default NULL,
  PRIMARY KEY  (`dataset_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

--
-- Table structure for table `dpMap`
--

CREATE TABLE `dpMap` (
  `dataset_id` int(10) unsigned NOT NULL,
  `parameter_id` int(10) unsigned NOT NULL,
  PRIMARY KEY  (`dataset_id`,`parameter_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

--
-- Table structure for table `granule`
--
CREATE TABLE `granule` (
  `granule_id` int(10) unsigned NOT NULL auto_increment,
  `dataset_id` int(10) unsigned default NULL,
  `filename` varchar(255) NOT NULL,
  PRIMARY KEY  (`granule_id`),
  KEY `dataset_id` (`dataset_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

--
-- Table structure for table `parameter`
--

CREATE TABLE `parameter` (
  `parameter_id` int(10) unsigned NOT NULL auto_increment,
  `longName` varchar(120) default NULL,
  `shortName` varchar(60) default NULL,
  `description` text,
  `referenceURL` varchar(255) default NULL,
  `cellMethod` text,
  `missingDataFlag` float default NULL,
  `units` varchar(60) default NULL,
  `verticalUnits` varchar(120) default NULL,
  `database` varchar(80) default NULL,
  `dataset_id` int(10) unsigned NOT NULL,
  PRIMARY KEY  (`parameter_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;
