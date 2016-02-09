/*
 * Copyright 2011 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

var apiApp = angular.module('apiApp', []);

apiApp.controller('msgControl', function ($scope, $sce) {
  for (var key in apiData) {
  	$scope[key] = apiData[key];
  }
  $scope.message.description = $sce.trustAsHtml($scope.message.description);

  var elemArray = $scope.message.fields;
  for (var j = 0; j < elemArray.length; j++) {
    var elem = elemArray[j];
    elem.description = $sce.trustAsHtml(elem.description);
  }

});

apiApp.controller('enumControl', function ($scope, $sce) {
  for (var key in apiData) {
  	$scope[key] = apiData[key];
  }
  $scope.description = $sce.trustAsHtml($scope.description);

  var described = ["methods", "fields"];

  for (var i = 0; i < described.length; i++) {
  	var arrayName = described[i];
  	if ($scope[arrayName]) {
  		var elemArray = $scope[arrayName];
  		for (var j = 0; j < elemArray.length; j++) {
  			var elem = elemArray[j];
	  		elem.description = $sce.trustAsHtml(elem.description);
  		}
  	}
  }

});

apiApp.controller('serviceControl', function ($scope, $sce) {
  for (var key in apiData) {
  	$scope[key] = apiData[key];
  }
  $scope.description = $sce.trustAsHtml($scope.description);

  angular.forEach($scope.nested, function(msg, name) {
    msg.description = $sce.trustAsHtml(msg.description);

    var elemArray = msg.fields;
    for (var j = 0; j < elemArray.length; j++) {
      var elem = elemArray[j];
      elem.description = $sce.trustAsHtml(elem.description);
    }
  });

  angular.forEach($scope.methods, function(meth, name) {
      meth.description = $sce.trustAsHtml(meth.description);
  });

});

apiApp.controller('menuControl', function ($scope) {
  for (var key in menuData) {
  	$scope[key] = menuData[key];
  }
});