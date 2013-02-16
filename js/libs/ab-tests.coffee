# Caveats:
#   You can't change the options once they're set, you need to define a new test
#
# Example setup in viewModel:
#   @ab = new(CI.ABTests({daniel_test: [true, false]})).ab_tests
#
# Example usage in view:
#   %p{href: "#", data-bind: "if: ab().daniel_test"}
#     This is the text that will show up if option is set to true
#   %p{href: "#", data-bind: "if: ab().daniel_test"}
#     This is the text that will show up if option is set to false

CI.ABTests = class ABTests
  constructor: (test_definitions, options={}) ->

    @cookie_name = options.cookie_name || "ab_test_user_seed"

    @user_seed = @get_user_seed()

    # defs don't need to be an observable, but we may want to do
    # inference in the future. Better to set it up now.
    @test_definitions = ko.observable(test_definitions)

    # @ab_tests is an object with format {'test_name': "chosen_option", ...}
    # Again, no need to be observable yet
    @ab_tests = ko.observable({})

    @setup_tests()

  get_user_seed: =>
    if not $.cookie(@cookie_name)?
      $.cookie(@cookie_name, Math.random(), {expires: 365, path: "/"})
    parseFloat($.cookie(@cookie_name))

  option_index: (seed, name, options) =>
    Math.abs(CryptoJS.MD5("#{seed}#{name}").words[0] % options.length)

  setup_tests: () =>
    tests = {}
    for name, options of @test_definitions()

      value = options[@option_index(@user_seed, name, options)]

      tests[name] = ko.observable(value)

      console.log "Set (or reseting) A/B test '#{name}' to value '#{value}'"

    @ab_tests(tests)
    _kmq.push ["set", tests]