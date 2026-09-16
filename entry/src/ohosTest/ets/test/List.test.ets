import gameRepositoryTest from './GameRepository.test';
import formatTest from './Format.test';

export default function testsuite() {
  gameRepositoryTest();
  formatTest();
}
