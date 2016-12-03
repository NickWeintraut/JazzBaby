// Define the `phonecatApp` module
var jazzBabyApp = angular.module('jazzBabyApp', []);

// Define the `PhoneListController` controller on the `phonecatApp` module
jazzBabyApp.controller('jazzBabyController', function JazzBabyController($scope) {

	$scope.stave = {};
  
	$scope.render = function (){
	  
	}
}).directive('vextab', function($compile){
    //console.log("rendering vextab");
    var canvas = document.createElement('canvas');
    return{
        restrict: 'E',
        transclude: true,
        scope: {},
        link: function(scope, element, attrs){
                try {
                	renderer = new Vex.Flow.Renderer( canvas,
                            //Vex.Flow.Renderer.Backends.SVG);
                            Vex.Flow.Renderer.Backends.CANVAS);
                	renderer.resize(500,500);
              		var context = renderer.getContext();
              		context.setFont("Arial", 10, "").setBackgroundFillStyle("#eed");
              		var stave = new Vex.Flow.Stave(10,40,400);
              		stave.addClef("treble").addTimeSignature("4/4");
              		console.log("we initialized!");
                	scope.context = context;
                	scope.stave = stave;
                	stave.setContext(context).draw();
                	console.log("we rendered!");
                }
                catch (e) {
                    console.log("Error");
                    console.log(e);
                }
         //element.appendChild(canvas);
         $compile(canvas)(scope);
         //element.append(canvas);
         element.replaceWith(canvas);
         //console.log("vextab processing");
        }
    }
});