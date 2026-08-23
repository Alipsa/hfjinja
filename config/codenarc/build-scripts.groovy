ruleset {
  ruleset('rulesets/basic.xml') {
    exclude 'DuplicateStringLiteral'
  }
  ruleset('rulesets/dry.xml') {
    exclude 'DuplicateNumberLiteral'
    exclude 'DuplicateStringLiteral'
  }
  ruleset('rulesets/unnecessary.xml') {
    exclude 'UnnecessaryCollectCall'
    exclude 'UnnecessaryGString'
    exclude 'UnnecessaryGetter'
    exclude 'UnnecessaryReturnKeyword'
  }
}
