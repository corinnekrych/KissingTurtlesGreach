package kissingturtles


import grails.converters.JSON
import grails.validation.ValidationErrors

import org.codehaus.groovy.grails.web.json.JSONObject;
import org.springframework.dao.DataIntegrityViolationException

import dsl.Position
import dsl.Turtle
import dsl.DslScript

class GameController {

    static allowedMethods = [save: "POST", update: "POST", delete: "POST"]

    def gameService
    def wallGeneratorService

    def index() {
        redirect(action: "list", params: params)
    }
	
    def list() {
      params.max = Math.min(params.max ? params.int('max') : 10, 100)
      render Game.list(params) as JSON
    }

    def run() {
        println "in the inputs" + params

        def game = Game.findById(params.gameId)

        Position franklinPosition = new Position(game.franklinX, game.franklinY, game.franklinRot, game.franklinDir)
        Position emilyPosition = new Position(game.emilyX, game.emilyY, game.emilyRot, game.emilyDir)

        def scriptInstance = new DslScript(params)
        def script = scriptInstance.content
        def turtle

        println "my game is ${game.id} with maze ${game.mazeTTT}"

        if (game.user1 == params.user) {
            turtle = new Turtle("franklin", "image", franklinPosition, game.mazeTTT)
        } else if ((game.user2 == params.user)) {
            turtle = new Turtle("emily", "image", emilyPosition, game.mazeTTT)
        }

        def binding = new Binding([
                turtle: turtle,
                left: dsl.Direction.left,
                right: dsl.Direction.right,
                down: dsl.Direction.down,
                up: dsl.Direction.up,
                move: turtle.&move,
                by: turtle.&by
        ])
        def shell = new GroovyShell(binding)
        shell.evaluate(script)
        def result = binding.getVariable('turtle').result

        def conf = gameService.runFormatting(game, turtle, result)

        // save current position
        if (!game.save(flush: true)) {
            ValidationErrors validationErrors = game.errors
            render validationErrors as JSON
        }

        // notify when turtle moves
        event topic: "execute-game", data: conf
        println conf
        render conf
    }

    def save() {
        def size = 15
        JSONObject jsonObject = JSON.parse(params.game)

        // generate walls
        def whichMaze = wallGeneratorService.randomWallConfiguration()
        def walls = wallGeneratorService.getWalls(whichMaze)

        // generate position for Franklin and the meeting point
        Position franklinPosition = new Position().random(size, walls)
        Position treePosition = new Position().random(size, walls)

        // format into json like
        def mazeDefinition = gameService.createFormatting(walls, franklinPosition, treePosition)

        // create new Game
        Game gameInstance = new Game()
        gameInstance.user1 = jsonObject.entrySet().iterator().next().value
        gameInstance.mazeDefinition = mazeDefinition
        gameInstance.mazeTTT = whichMaze
        gameInstance.franklinX = franklinPosition.x
        gameInstance.franklinY = franklinPosition.y
        gameInstance.franklinRot = franklinPosition.rotation
        gameInstance.franklinDir = franklinPosition.direction
        gameInstance.treeX = treePosition.x
        gameInstance.treeY = treePosition.y
        gameInstance.treeRot = treePosition.rotation
        gameInstance.treeDir = treePosition.direction
        if (!gameInstance.save(flush: true)) {
            ValidationErrors validationErrors = gameInstance.errors
            render validationErrors as JSON
        }
        // notify when first turtle create a new game
        event topic:"save-game", data: gameInstance
        render gameInstance as JSON
    }
    
    def show() {
      def gameInstance = Game.get(params.id)
      if (!gameInstance) {
        flash.message = message(code: 'default.not.found.message', args: [message(code: 'game.label', default: 'Game'), params.id])
        render flash as JSON
      }
      render GameInstance as JSON
    }
    def update() {
        JSONObject jsonObject = JSON.parse(params.game)
        def gameInstance = Game.get(jsonObject.gameId)

        // generate position for Emily (anywhere except on the walls)
        def walls = wallGeneratorService.getWalls(gameInstance.mazeTTT)
        Position emilyPosition = new Position().random(15, walls)
        gameInstance.emilyX = emilyPosition.x
        gameInstance.emilyY = emilyPosition.y
        gameInstance.emilyRot = emilyPosition.rotation
        gameInstance.emilyDir = emilyPosition.direction

        gameInstance.mazeDefinition = gameService.updateFormatting(gameInstance, emilyPosition)

        if (!gameInstance) {
            flash.message = message(code: 'default.not.found.message', args: [message(code: 'game.label', default: 'Game'), params.id])
            render flash as JSON
        }

        // only two users game
        if (gameInstance.user2) {
            flash.message = message(code: 'default.game.already.started', args: [message(code: 'game.label', default: 'Game'), params.id])
            render flash as JSON
        }
        gameInstance.user2 = jsonObject.get("user2")

        // save game
        if (!gameInstance.save(flush: true)) {
            ValidationErrors validationErrors = gameInstance.errors
            render validationErrors as JSON
        }

        // notify that Emily enters the game
        event topic: "update-game", data: gameInstance
        render gameInstance as JSON
    }

    def delete() {
      def gameInstance = Game.get(params.id)
      
      if (!gameInstance) {
        flash.message = message(code: 'default.not.found.message', args: [message(code: 'game.label', default: 'Game'), params.id])
        render flash as JSON
      }
      try {
        gameInstance.delete(flush: true)
      }
      catch (DataIntegrityViolationException e) {
        flash.message = message(code: 'default.not.deleted.message', args: [message(code: 'game.label', default: 'Game'), params.id])
        render flash as JSON
      }

      event topic:"delete-game", data: gameInstance

      render gameInstance as JSON
    }
}
